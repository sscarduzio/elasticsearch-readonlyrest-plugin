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

import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.util.Set;


/**
 * Created by sscarduzio on 26/03/2016.
 */
public class KibanaAccessRule extends Rule {

  private final Logger logger = Loggers.getLogger(this.getClass());
  private static final  Actions actions = new Actions();

  private String kibanaIndex = ".kibana";

  private Boolean canModifyKibana;

  static class Actions {
    private MatcherWithWildcards RO;
    private MatcherWithWildcards RW;
    private MatcherWithWildcards CLUSTER;

    Actions() {
      Set<String> kibanaServerClusterActions = Sets.newHashSet(
          "cluster:monitor/nodes/info",
          "cluster:monitor/health");

      Set<String> kibanaActionsRO = Sets.newHashSet(
          "indices:admin/exists",
          "indices:admin/mappings/fields/get*",
          "indices:admin/validate/query",
          "indices:data/read/field_stats",
          "indices:data/read/search",
          "indices:data/read/msearch",
          "indices:admin/get",
          "indices:admin/refresh*",
          "indices:data/read/*"
      );

      Set<String> kibanaActionsRW = Sets.newHashSet(
          "indices:admin/create",
          "indices:admin/exists",
          "indices:admin/mapping/put",
          "indices:data/write/delete",
          "indices:data/write/index",
          "indices:data/write/update"
      );

      kibanaActionsRW.addAll(kibanaActionsRO);

      RO = new MatcherWithWildcards(kibanaActionsRO);
      RW = new MatcherWithWildcards(kibanaActionsRW);
      CLUSTER = new MatcherWithWildcards(kibanaServerClusterActions);
    }
  }

  public KibanaAccessRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    String tmp = s.get(getKey());
    if (Strings.isNullOrEmpty(tmp)) {
      throw new RuleNotConfiguredException();
    }
    tmp = tmp.toLowerCase();
    if ("ro".equals(tmp)) {
      canModifyKibana = false;
    } else if ("rw".equals(tmp)) {
      canModifyKibana = true;
    } else if ("ro+".equals(tmp)) {
      throw new RuleConfigurationError("invalid configuration: 'ro+' is no longer supported. Use 'rw' instead", null);
    } else {
      throw new RuleConfigurationError("invalid configuration: use either 'ro' or 'rw'. Found: + " + tmp, null);
    }
    tmp = s.get("kibana_index");
    if (!Strings.isNullOrEmpty(tmp)) {
      kibanaIndex = tmp;
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {

    String a = rc.getAction();

    // If this rule is active, it's at least allowing read actions for any indices.
    if (actions.RO.match(a) || actions.CLUSTER.match(a)) {
      return MATCH;
    }

    Set<String> indices = rc.getIndices();

    // Allow other actions if devnull is targeted to readers and writers
    if (indices.contains(".kibana-devnull")) {
      return MATCH;
    }

    // Handle requests to kibanaIndex
    if (indices.size() == 1 && indices.contains(kibanaIndex)) {

      // Write actions are only allowed for kibanaIndex
      if(canModifyKibana && actions.RW.match(a)){
        logger.debug("allowing RW req: " + rc);
        return MATCH;
      }

    }

    logger.debug("KIBANA ACCESS DENIED " + rc);
    return NO_MATCH;
  }
}
