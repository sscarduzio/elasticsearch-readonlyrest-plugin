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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import java.util.Optional;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;

import com.google.common.base.Strings;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.UnsupportedJwtException;

public class JwtAuthSyncRule extends SyncRule implements UserRule, Authentication {

  private static final Logger logger = Loggers.getLogger(JwtAuthSyncRule.class);
  private static final String RULE_NAME = "jwt_auth";

  private byte[] key;
  private Optional<String> userClaim;

  @Override
  public RuleExitResult match(RequestContext rc) {
    Optional<String> token = Optional.of(rc.getHeaders())
                  .map(m -> m.get("Authorization"))
                  .flatMap(JwtAuthSyncRule::extractToken);

    if (!token.isPresent()) {
      logger.debug("Authorization header is missing or does not contain a bearer token");
      return NO_MATCH;
    }

    try {
      Jws<Claims> jws = Jwts.parser()
        .setSigningKey(key)
        .parseClaimsJws(token.get());

      Optional<String> user = this.userClaim.map(claim -> jws.getBody().get(claim, String.class));
      if (userClaim.isPresent())
        if (!user.isPresent()) return NO_MATCH;
        else rc.setLoggedInUser(new LoggedUser(user.get()));

      return MATCH;
    } catch (ExpiredJwtException | UnsupportedJwtException | MalformedJwtException | SignatureException e) {
      return NO_MATCH;
    }
  }

  public static Optional<SyncRule> fromSettings(Settings s) {
    String key = s.get(RULE_NAME + ".signature.key");

    if (Strings.isNullOrEmpty(key))
      return Optional.empty();

    JwtAuthSyncRule rule = new JwtAuthSyncRule();
    rule.key = key.getBytes();
    rule.userClaim = Optional.ofNullable(s.get(RULE_NAME + ".user_claim"));
    return Optional.of(rule);
  }

  private static Optional<String> extractToken(String authHeader) {
    if (authHeader.startsWith("Bearer ")) {
      String token = authHeader.substring(7).trim();
      return Optional.ofNullable(Strings.emptyToNull(token));
    } else {
      return Optional.empty();
    }
  }
}
