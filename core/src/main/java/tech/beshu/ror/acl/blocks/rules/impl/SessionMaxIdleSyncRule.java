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

import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.RuleSettings;
import tech.beshu.ror.settings.rules.SessionMaxIdleRuleSettings;

import java.time.Duration;

/**
 * Created by sscarduzio on 03/01/2017.
 */
public class SessionMaxIdleSyncRule extends SyncRule {

  private final LoggerShim logger;
  private final ESContext context;
  private final Duration maxIdle;
  private final RuleSettings settings;

  public SessionMaxIdleSyncRule(SessionMaxIdleRuleSettings s, ESContext context) {
    this.logger = context.logger(getClass());
    this.context = context;
    this.maxIdle = s.getMaxIdle();
    this.settings = s;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    SessionCookie c = new SessionCookie(rc, maxIdle.toMillis(), context);

    // 1 no cookie
    if (!c.isCookiePresent()) {
      c.setCookie();
      return MATCH;
    }

    // 2 OkCookie
    if (c.isCookieValid()) {
      // Postpone the expiry date: we want to reset the login only for users that are INACTIVE FOR a period of time.
      c.setCookie();
      return MATCH;
    }

    // 2' BadCookie
    if (c.isCookiePresent() && !c.isCookieValid()) {
      c.unsetCookie();
      return NO_MATCH;
    }
    logger.error("Session handling panic! " + c + " RC:" + rc);
    return NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}
