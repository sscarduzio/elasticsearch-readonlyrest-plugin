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
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.apache.commons.codec.binary.Base64;
import tech.beshu.ror.acl.blocks.rules.AsyncRule;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authentication;
import tech.beshu.ror.acl.definitions.DefinitionsFactory;
import tech.beshu.ror.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceClient;
import tech.beshu.ror.commons.domain.__old_LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.JwtAuthRuleSettings;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class JwtAuthSyncRule extends AsyncRule implements Authentication {

  private final LoggerShim logger;
  private final JwtAuthRuleSettings settings;
  private final ExternalAuthenticationServiceClient httpClient;

  public JwtAuthSyncRule(JwtAuthRuleSettings settings, ESContext context, DefinitionsFactory factory) {
    this.logger = context.logger(getClass());
    this.settings = settings;
    this.httpClient = settings.getExternalValidator().isPresent() ? factory.getClient(settings) : null;
  }

  private static Optional<String> extractToken(String authHeader) {
    if (authHeader.startsWith("Bearer ")) {
      String token = authHeader.substring(7).trim();
      return Optional.ofNullable(Strings.emptyToNull(token));
    }

    return Optional.ofNullable(Strings.emptyToNull(authHeader));
  }

  @Override
  public CompletableFuture<RuleExitResult> match(__old_RequestContext rc) {
    Optional<String> token = Optional.of(rc.getHeaders()).map(m -> m.get(settings.getHeaderName()))
                                     .flatMap(JwtAuthSyncRule::extractToken);

    /*
      JWT ALGO    FAMILY
      =======================
      NONE        None

      HS256       HMAC
      HS384       HMAC
      HS512       HMAC

      RS256       RSA
      RS384       RSA
      RS512       RSA
      PS256       RSA
      PS384       RSA
      PS512       RSA

      ES256       EC
      ES384       EC
      ES512       EC
    */

    if (!token.isPresent()) {
      logger.debug("Authorization header is missing or does not contain a bearer token");
      return CompletableFuture.completedFuture(NO_MATCH);
    }

    try {

      JwtParser parser = Jwts.parser();

      // Defaulting to HMAC for backward compatibility
      String algoFamily = settings.getAlgo().map(String::toUpperCase).orElse("HMAC");
      if (settings.getKey() == null) {
        algoFamily = "NONE";
      }

      if (!"NONE".equals(algoFamily)) {

        if ("RSA".equals(algoFamily)) {
          try {
            byte[] keyBytes = Base64.decodeBase64(settings.getKey());
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(keyBytes));
            parser.setSigningKey(pubKey);
          } catch (GeneralSecurityException gso) {
            throw new RuntimeException(gso);
          }
        }

        else if ("EC".equals(algoFamily)) {
          try {
            byte[] keyBytes = Base64.decodeBase64(settings.getKey());
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(keyBytes));
            parser.setSigningKey(pubKey);
          } catch (GeneralSecurityException gso) {
            throw new RuntimeException(gso);
          }
        }

        else if ("HMAC".equals(algoFamily)) {
          parser.setSigningKey(settings.getKey());
        }
        else {
          throw new RuntimeException("unrecognised algorithm family " + algoFamily + ". Should be either of: HMAC, EC, RSA, NONE");
        }
      }

      Claims jws;
      if (settings.getKey() != null) {
        jws = parser.parseClaimsJws(token.get()).getBody();
      }
      else {
        String[] ar = token.get().split("\\.");
        if (ar.length < 2) {
          // token is not a valid JWT
          return CompletableFuture.completedFuture(NO_MATCH);
        }
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
          rc.setLoggedInUser(new __old_LoggedUser(user.get()));
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
