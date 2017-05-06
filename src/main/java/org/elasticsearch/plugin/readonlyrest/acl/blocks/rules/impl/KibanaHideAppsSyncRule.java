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

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.settings.rules.KibanaHideAppsRuleSettings;

import java.util.Optional;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class KibanaHideAppsSyncRule extends SyncRule {

  private static final String KIBANA_HIDE_APPS_HEADER = "x-kibana-hide-apps";

  private final Logger logger;
  private final String hiddenApps;

  public KibanaHideAppsSyncRule(KibanaHideAppsRuleSettings s, ESContext context) {
    logger = context.logger(getClass());
    hiddenApps = Joiner.on(",").join(s.getKibanaHideApps());
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    Optional<LoggedUser> loggedInUser = rc.getLoggedInUser();
    if (Strings.isNullOrEmpty(hiddenApps) || !loggedInUser.isPresent()) {
      return MATCH;
    }

    logger.info("setting hidden apps for user " + loggedInUser.get() + ": " + hiddenApps);
    rc.setResponseHeader(KIBANA_HIDE_APPS_HEADER, hiddenApps);

    // This is a side-effect only rule, will always match
    return MATCH;
  }
}
