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

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;

import java.util.Optional;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class ActionsSyncRule extends SyncRule {

  private static final ESLogger logger =  Loggers.getLogger(ActionsSyncRule.class);

  protected MatcherWithWildcards m;

  public ActionsSyncRule(Settings s) throws RuleNotConfiguredException {
    super();
    m = MatcherWithWildcards.fromSettings(s, getKey());
  }

  public static Optional<ActionsSyncRule> fromSettings(Settings s) {
    try {
      return Optional.of(new ActionsSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
      return Optional.empty();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    if (m.match(rc.getAction())) {
      return MATCH;
    }
    logger.debug("This request uses the action'" + rc.getAction() + "' and none of them is on the list.");
    return NO_MATCH;
  }
}
