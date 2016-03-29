package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Lists;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.util.List;

/**
 * Created by sscarduzio on 26/03/2016.
 */
public class KibanaIndicesRule extends KibanaServerRule {
  private static List<String> kibanaGlobalIndexUserActions = Lists.newArrayList(
      "indices:admin/mappings/fields/get",
      "indices:admin/validate/query",
      "indices:data/read/search",
      "indices:data/read/msearch",
      "indices:admin/get"
  );

  protected List<String> indicesToMatch;

  public KibanaIndicesRule(Settings s) throws RuleNotConfiguredException{
    super(s);
    String[] a = s.getAsArray(KEY);
    if (a != null && a.length > 0) {
      indicesToMatch = Lists.newArrayList();
      for (int i = 0; i < a.length; i++) {
        if (!ConfigurationHelper.isNullOrEmpty(a[i])) {
          indicesToMatch.add(a[i].trim());
        }
      }
    } else {
      throw new RuleNotConfiguredException();
    }
    // Kibana user needs this
    indicesToMatch.add("*");
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    if(super.match(rc).isMatch()){
      return MATCH;
    }
    if(!kibanaGlobalIndexUserActions.contains(rc.getAction())) return NO_MATCH;

    for (String idx : rc.getIndices()){
      if(!indicesToMatch.contains(idx) ) return NO_MATCH;
    }

    return NO_MATCH;
  }
}
