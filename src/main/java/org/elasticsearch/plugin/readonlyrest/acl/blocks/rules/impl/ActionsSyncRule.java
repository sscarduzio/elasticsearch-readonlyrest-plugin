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

import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ActionsRuleSettings;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class ActionsSyncRule extends SyncRule {

  private final Logger logger;
  private final MatcherWithWildcards matcher;

  public ActionsSyncRule(ActionsRuleSettings s, ESContext context) {
    logger = context.logger(getClass());
    matcher = new MatcherWithWildcards(s.getActions());
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    if (matcher.match(rc.getAction())) {
      return MATCH;
    }
    logger.debug("This request uses the action'" + rc.getAction() + "' and none of them is on the list.");
    return NO_MATCH;
  }
}
