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
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.UnsupportedJwtException;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.UserRule;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authentication;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.commons.domain.LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.RorKbnAuthRuleSettings;

import java.security.AccessController;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivilegedAction;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RorKbnAuthSyncRule extends UserRule implements Authentication {

  private final LoggerShim logger;
  private final RorKbnAuthRuleSettings settings;
  private final Optional<Key> signingKeyForAlgo;

  public RorKbnAuthSyncRule(RorKbnAuthRuleSettings settings, ESContext context) {
    this.logger = context.logger(getClass());
    this.settings = settings;
    this.signingKeyForAlgo = getSigningKeyForAlgo();
  }

  private static Optional<String> extractToken(String authHeader) {
    if (authHeader.startsWith("Bearer ")) {
      String token = authHeader.substring(7).trim();
      return Optional.ofNullable(Strings.emptyToNull(token));
    }

    return Optional.ofNullable(Strings.emptyToNull(authHeader));
  }

  private Optional<Key> getSigningKeyForAlgo() {
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
  public RuleExitResult match(RequestContext rc) {
    Optional<String> token = Optional.of(rc.getHeaders()).map(m -> m.get(settings.getHeaderName()))
                                     .flatMap(RorKbnAuthSyncRule::extractToken);

    if (!token.isPresent()) {
      logger.debug("Authorization header is missing or does not contain a bearer token");
      return NO_MATCH;
    }

    try {
      Jws<Claims> jws = AccessController.doPrivileged((PrivilegedAction<Jws<Claims>>) () -> {
        JwtParser parser = Jwts.parser();
        if (signingKeyForAlgo.isPresent()) {
          parser.setSigningKey(signingKeyForAlgo.get());
        }
        else {
          parser.setSigningKey(settings.getKey());
        }
        return parser.parseClaimsJws(token.get());
      });

      Optional<String> user = settings.getUserClaim().map(claim -> jws.getBody().get(claim, String.class));
      if (settings.getUserClaim().isPresent())
        if (!user.isPresent())
          return NO_MATCH;
        else
          rc.setLoggedInUser(new LoggedUser(user.get()));

      Optional<Set<String>> roles = this.extractRoles(jws);
      if (settings.getRolesClaim().isPresent() && !roles.isPresent())
        return NO_MATCH;
      if (!settings.getRoles().isEmpty()) {
        if (!roles.isPresent()) {
          return NO_MATCH;
        }
        else {
          Set<String> r = roles.get();
          if (r.isEmpty() || Sets.intersection(r, settings.getRoles()).isEmpty())
            return NO_MATCH;
        }
      }
      rc.setResponseHeader(Constants.HEADER_USER_ORIGIN, jws.getBody().get(Constants.HEADER_USER_ORIGIN, String.class));
      return MATCH;
    } catch (ExpiredJwtException | UnsupportedJwtException | MalformedJwtException | SignatureException e) {
      return NO_MATCH;
    }
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  @SuppressWarnings("unchecked")
  private Optional<Set<String>> extractRoles(Jws<Claims> jws) {
    // Get claim roles
    Optional<Object> rolesObj = settings.getRolesClaim().map(claim -> {
      String[] path = claim.split("[.]");
      if (path.length < 2)
        return jws.getBody().get(claim, Object.class);
      else {
        // Ok we need to parse all sub sequent path
        Object value = jws.getBody().get(path[0], Object.class);
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