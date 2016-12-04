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

import com.google.common.collect.Lists;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.util.List;


/**
 * Created by sscarduzio on 26/03/2016.
 */
public class KibanaAccessRule extends Rule {

  private final static ESLogger logger = Loggers.getLogger(KibanaAccessRule.class);

  private static List<String> kibanaServerClusterActions = Lists.newArrayList(
      "cluster:monitor/nodes/info",
      "cluster:monitor/health");

  private static List<String> kibanaActionsRO = Lists.newArrayList(
      "indices:admin/exists",
      "indices:admin/mappings/fields/get",
      "indices:admin/validate/query",
      "indices:data/read/field_stats",
      "indices:data/read/search",
      "indices:data/read/msearch",
      "indices:admin/get",
      "indices:admin/refresh",
      "indices:data/read/get",
      "indices:data/read/mget",
      "indices:data/read/mget[shard]",
      "indices:admin/mappings/fields/get[index]"
  );

  private static List<String> kibanaActionsRW = Lists.newArrayList(
      "indices:admin/create",
      "indices:admin/exists",
      "indices:admin/mapping/put",
      "indices:data/write/delete",
      "indices:data/write/index",
      "indices:data/write/update");

  static {
    kibanaActionsRW.addAll(kibanaActionsRO);
  }

  private List<String> allowedActions = kibanaActionsRO;

  private String kibanaIndex = ".kibana";
  private boolean canModifyKibana = false;

  public KibanaAccessRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    String tmp = s.get(KEY);
    if (ConfigurationHelper.isNullOrEmpty(tmp)) {
      throw new RuleNotConfiguredException();
    }
    tmp = tmp.toLowerCase();

    if ("ro".equals(tmp)) {
      allowedActions = kibanaActionsRO;
    } else if ("rw".equals(tmp)) {
      allowedActions = kibanaActionsRW;
      canModifyKibana = true;
    } else if ("ro+".equals(tmp)) {
      tmp = s.get("kibana_index");
      if (!ConfigurationHelper.isNullOrEmpty(tmp)) {
        kibanaIndex = tmp;
      }
      allowedActions = kibanaActionsRO;
      canModifyKibana = true;
    } else {
      throw new RuleConfigurationError("invalid configuration: use either 'ro' or 'rw'. Found: + " + tmp, null);
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {

    if (kibanaActionsRO.contains(rc.getAction()) || kibanaServerClusterActions.contains(rc.getAction())) {
      return MATCH;
    }

    // Allow other actions if devnull is targeted to readers and writers
    if (rc.getIndices().contains(".kibana-devnull")) {
      return MATCH;
    }

    if (canModifyKibana && rc.getIndices().size() == 1 && rc.getIndices().contains(kibanaIndex) && kibanaActionsRW.contains(rc.getAction())) {
        logger.debug("allowing RW req: " + rc);
        return MATCH;
    }

    logger.debug("KIBANA ACCESS DENIED " + rc);
    return NO_MATCH;
  }
}
