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
      "indices:data/read/mget"
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
  boolean canModifyKibana = false;

  public KibanaAccessRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    String tmp = s.get(KEY);
    if (ConfigurationHelper.isNullOrEmpty(tmp)) {
      throw new RuleNotConfiguredException();
    }

    tmp = tmp.toLowerCase();

    if (tmp.equals("ro")) {
      allowedActions = kibanaActionsRO;
    } else if (tmp.equals("rw")) {
      allowedActions = kibanaActionsRW;
      canModifyKibana = true;
    } else if (tmp.equals("ro+")) {
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

    // Cluster actions are always allowed in both modes
    for (String k : kibanaServerClusterActions) {
      if (rc.getAction().contains(k)) {
        return MATCH;
      }
    }
    for (String k : allowedActions) {
      if (rc.getAction().contains(k)) {
        return MATCH;
      }
    }
    if(canModifyKibana) {
      for (String i : rc.getIndices()) {
        if (kibanaIndex.equals(i) || ".kibana-devnull".equals(i)) {
          return MATCH;
        }
      }
    }

    // Allow devnull to anybody in RW
    for (String i : rc.getIndices()) {
      if (".kibana-devnull".equals(i)) {
        return MATCH;
      }
    }

    logger.debug("KIBANA ACCESS DENIED " + rc);
    return NO_MATCH;
  }
}
