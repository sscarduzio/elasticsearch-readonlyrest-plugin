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
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.Strings;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;

import java.util.Arrays;
import java.util.Optional;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class KibanaHideAppsSyncRule extends SyncRule {

  private static final String KIBANA_HIDE_APPS_HEADER = "x-kibana-hide-apps";
  private final Logger logger = Loggers.getLogger(this.getClass());
  private final String hiddenApps;

  public KibanaHideAppsSyncRule(Settings s) throws RuleNotConfiguredException {
    super();
    // Will work fine also with single strings (non array) values.
    String[] a = s.getAsArray(getKey());

    if (a == null || a.length == 0) {
      throw new RuleNotConfiguredException();
    }
    hiddenApps = Joiner.on(",").join(Arrays.asList(a));
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    Optional<LoggedUser> loggedInUser = rc.getLoggedInUser();
    if (Strings.isEmpty(hiddenApps) || !loggedInUser.isPresent()) {
      return MATCH;
    }

    logger.info("setting hidden apps for user " + loggedInUser.get() + ": " + hiddenApps);
    rc.setResponseHeader(KIBANA_HIDE_APPS_HEADER, hiddenApps);

    // This is a side-effect only rule, will always match
    return MATCH;
  }
}
