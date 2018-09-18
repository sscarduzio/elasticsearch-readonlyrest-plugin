/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.UnsupportedJwtException;
import tech.beshu.ror.acl.blocks.rules.AsyncRule;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authentication;
import tech.beshu.ror.acl.definitions.DefinitionsFactory;
import tech.beshu.ror.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceClient;
import tech.beshu.ror.commons.domain.LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.JwtAuthRuleSettings;

import java.security.Key;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class JwtAuthSyncRule extends AsyncRule implements Authentication {

  private final LoggerShim logger;
  private final JwtAuthRuleSettings settings;
  private final Optional<Key> signingKeyForAlgo;
  private final ExternalAuthenticationServiceClient httpClient;

  public JwtAuthSyncRule(JwtAuthRuleSettings settings, ESContext context, DefinitionsFactory factory) {
    this.logger = context.logger(getClass());
    this.settings = settings;
    this.signingKeyForAlgo = getSigningKeyForAlgo();
    this.httpClient = settings.getExternalValidator().isPresent() ? factory.getClient(settings) : null;
  }

  private static Optional<String> extractToken(String authHeader) {
    if (authHeader.startsWith("Bearer ")) {
      String token = authHeader.substring(7).trim();
      return Optional.ofNullable(Strings.emptyToNull(token));
    }

    return Optional.ofNullable(Strings.emptyToNull(authHeader));
  }

  private Optional<Key> getSigningKeyForAlgo() {
    if (settings.getKey() == null) {
      return Optional.empty();
    }
    if (settings.getAlgo().isPresent()) {
      try {
        byte[] decoded = Base64.getDecoder().decode(settings.getKey());
        X509EncodedKeySpec X509publicKey = new X509EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance(settings.getAlgo().get());
        return Optional.of(kf.generatePublic(X509publicKey));
      } catch (Exception e) {
        logger.error(e.getMessage());
      }
    }
    return Optional.empty();
  }

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    Optional<String> token = Optional.of(rc.getHeaders()).map(m -> m.get(settings.getHeaderName()))
                                     .flatMap(JwtAuthSyncRule::extractToken);

    if (!token.isPresent()) {
      logger.debug("Authorization header is missing or does not contain a bearer token");
      return CompletableFuture.completedFuture(NO_MATCH);
    }

    try {

      JwtParser parser = Jwts.parser();

      if (signingKeyForAlgo.isPresent()) {
        parser.setSigningKey(signingKeyForAlgo.get());
      }
      else {
        if (settings.getKey() != null) {
          parser.setSigningKey(settings.getKey());
        }
      }

      Claims jws = null;
      if (settings.getKey() != null) {
        jws = parser.parseClaimsJws(token.get()).getBody();
      }
      else {
        String[] ar = token.get().split("\\.");
        String tokenNoSig = ar[0] + "." + ar[1] + ".";
        jws = parser.parseClaimsJwt(tokenNoSig).getBody();
      }

      Claims finalJws = jws;
      Optional<String> user = settings.getUserClaim().map(claim -> finalJws.get(claim, String.class));
      if (settings.getUserClaim().isPresent())
        if (!user.isPresent()) {
          return CompletableFuture.completedFuture(NO_MATCH);
        }
        else {
          rc.setLoggedInUser(new LoggedUser(user.get()));
        }

      Optional<Set<String>> roles = this.extractRoles(jws);
      if (settings.getRolesClaim().isPresent() && !roles.isPresent()) {
        return CompletableFuture.completedFuture(NO_MATCH);
      }
      if (!settings.getRoles().isEmpty()) {
        if (!roles.isPresent()) {
          return CompletableFuture.completedFuture(NO_MATCH);
        }
        else {
          Set<String> r = roles.get();
          if (r.isEmpty() || Sets.intersection(r, settings.getRoles()).isEmpty())
            return CompletableFuture.completedFuture(NO_MATCH);
        }
      }

      if (settings.getExternalValidator().isPresent()) {
        return httpClient.authenticate("x", token.get())
                         .thenApply(resp -> resp ? MATCH : NO_MATCH);
      }
      return CompletableFuture.completedFuture(MATCH);

    } catch (ExpiredJwtException | UnsupportedJwtException | MalformedJwtException | SignatureException e) {
      return CompletableFuture.completedFuture(NO_MATCH);
    }
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  @SuppressWarnings("unchecked")
  private Optional<Set<String>> extractRoles(Claims jws) {
    // Get claim roles
    Optional<Object> rolesObj = settings.getRolesClaim().map(claim -> {
      String[] path = claim.split("[.]");
      if (path.length < 2)
        return jws.get(claim, Object.class);
      else {
        // Ok we need to parse all sub sequent path
        Object value = jws.get(path[0], Object.class);
        int i = 1;
        while (i < path.length && value != null && value instanceof Map<?, ?>) {
          value = ((Map<String, Object>) value).get(path[i]);
          i++;
        }
        return value;
      }
    });

    // Casting
    return rolesObj.flatMap(value -> {
      Set<String> set = new HashSet<>();

      if (value instanceof Collection<?>) {
        set.addAll((Collection<String>) value);
      }
      else if (value instanceof String) {
        set.add((String) value);
      }
      if (set.isEmpty())
        return Optional.empty();
      return Optional.of(set);
    });
  }
}