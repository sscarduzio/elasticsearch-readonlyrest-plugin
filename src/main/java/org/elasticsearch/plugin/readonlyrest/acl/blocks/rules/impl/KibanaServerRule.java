package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.util.List;

/**
 * Created by sscarduzio on 26/03/2016.
 */
public class KibanaServerRule extends Rule {

  static List<String> kibanaIndices = Lists.newArrayList(".kibana",".kibana-devnull");

  private static List<String> kibanaServerClusterActions = Lists.newArrayList(
      "cluster:monitor/nodes/info",
      "cluster:monitor/health");


  private static List<String> kibanaServerActions = Lists.newArrayList(
      "indices:admin/create",
      "indices:admin/exists",
      "indices:admin/mapping/put",
      "indices:admin/mappings/fields/get",
      "indices:admin/refresh",
      "indices:admin/validate/query",
      "indices:data/read/get",
      "indices:data/read/mget",
      "indices:data/read/search",
      "indices:data/read/msearch",
      "indices:data/write/delete",
      "indices:data/write/index",
      "indices:data/write/update");

  public KibanaServerRule(Settings s) throws RuleNotConfiguredException{
    super(s);
    try{
      if(!s.getAsBoolean(KEY, false)){
        throw new RuleNotConfiguredException();
      }
    }
    catch(Throwable e){
      throw new RuleNotConfiguredException();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {

    if(kibanaServerClusterActions.contains(rc.getAction())) return MATCH;
    if(!kibanaServerActions.contains(rc.getAction())) return NO_MATCH;

    for(String s : rc.getIndices()){
        if(!kibanaIndices.contains(s)) return NO_MATCH;
    }

    return MATCH;
  }
}
