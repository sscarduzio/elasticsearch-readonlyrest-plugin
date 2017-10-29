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
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.UnsupportedJwtException;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.UserRule;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authentication;
import tech.beshu.ror.acl.domain.LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.JwtAuthRuleSettings;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Optional;

public class JwtAuthSyncRule extends UserRule implements Authentication {

  private final LoggerShim logger;
  private final JwtAuthRuleSettings settings;

  public JwtAuthSyncRule(JwtAuthRuleSettings settings, ESContext context) {
    this.logger = context.logger(getClass());
    this.settings = settings;
  }

  private static Optional<String> extractToken(String authHeader) {
    if (authHeader.startsWith("Bearer ")) {
      String token = authHeader.substring(7).trim();
      return Optional.ofNullable(Strings.emptyToNull(token));
    }

    return Optional.ofNullable(Strings.emptyToNull(authHeader));

  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    Optional<String> token = Optional.of(rc.getHeaders())
      .map(m -> m.get(settings.getHeaderName()))
      .flatMap(JwtAuthSyncRule::extractToken);

    if (!token.isPresent()) {
      logger.debug("Authorization header is missing or does not contain a bearer token");
      return NO_MATCH;
    }

    try {
      Jws<Claims> jws = AccessController.doPrivileged(
        (PrivilegedAction<Jws<Claims>>) () ->
          Jwts.parser()
            .setSigningKey(settings.getKey())
            .parseClaimsJws(token.get()));

      Optional<String> user = settings.getUserClaim().map(claim -> jws.getBody().get(claim, String.class));
      if (settings.getUserClaim().isPresent())
        if (!user.isPresent()) return NO_MATCH;
        else rc.setLoggedInUser(new LoggedUser(user.get()));

      return MATCH;
    } catch (ExpiredJwtException | UnsupportedJwtException | MalformedJwtException | SignatureException e) {
      return NO_MATCH;
    }
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}