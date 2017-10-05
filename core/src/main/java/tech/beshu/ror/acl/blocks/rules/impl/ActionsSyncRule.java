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

import tech.beshu.ror.commons.shims.ESContext;
import tech.beshu.ror.commons.shims.LoggerShim;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.ActionsRuleSettings;
import tech.beshu.ror.utils.MatcherWithWildcards;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class ActionsSyncRule extends SyncRule {

  private final LoggerShim logger;
  private final MatcherWithWildcards matcher;
  private final ActionsRuleSettings settings;

  public ActionsSyncRule(ActionsRuleSettings s, ESContext context) {
    logger = context.logger(getClass());
    matcher = new MatcherWithWildcards(s.getActions());
    settings = s;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    if (matcher.match(rc.getAction())) {
      return MATCH;
    }
    logger.debug("This request uses the action'" + rc.getAction() + "' and none of them is on the list.");
    return NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}
