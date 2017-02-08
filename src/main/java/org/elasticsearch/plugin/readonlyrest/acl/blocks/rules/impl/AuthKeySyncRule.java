/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.base.Charsets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.util.Base64;
import java.util.Map;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class AuthKeySyncRule extends SyncRule {
  private static final Logger logger = Loggers.getLogger(AuthKeySyncRule.class);

  protected String authKey;

  public AuthKeySyncRule(Settings s) throws RuleNotConfiguredException {
    super(s);

    String pAuthKey = s.get(this.getKey());
    if (pAuthKey != null && pAuthKey.trim().length() > 0) {
      authKey = Base64.getEncoder().encodeToString(pAuthKey.getBytes(Charsets.UTF_8));
    }
    else {
      throw new RuleNotConfiguredException();
    }
  }

  // Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==
  private static String extractAuthFromHeader(String authorizationHeader) {
    if (authorizationHeader == null || authorizationHeader.trim().length() == 0 || !authorizationHeader.contains("Basic "))
      return null;
    String interestingPart = authorizationHeader.split("Basic")[1].trim();
    if (interestingPart.length() == 0) {
      return null;
    }
    return interestingPart;
  }

  public static String getBasicAuthUser(Map<String, String> headers) {
    String authHeader = extractAuthFromHeader(headers.get("Authorization"));
    if (authHeader != null) {
      try {
        return new String(Base64.getDecoder().decode(authHeader)).split(":")[0];
      } catch (IllegalArgumentException e) {
        e.printStackTrace();
      }
    }
    return null;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    String authHeader = extractAuthFromHeader(rc.getHeaders().get("Authorization"));

    if (authHeader != null && logger.isDebugEnabled()) {
      try {
        logger.info("Login as: " + getBasicAuthUser(rc.getHeaders()) + " rc: " + rc);
      } catch (IllegalArgumentException e) {
        e.printStackTrace();
      }
    }

    if (authKey == null || authHeader == null) {
      return NO_MATCH;
    }

    String val = authHeader.trim();
    if (val.length() == 0) {
      return NO_MATCH;
    }

    return checkEqual(authHeader) ? MATCH : NO_MATCH;
  }

  protected boolean checkEqual(String provided) {
    return authKey.equals(provided);
  }
}