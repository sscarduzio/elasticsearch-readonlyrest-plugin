package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.util.List;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class ApiKeysRule extends Rule {

  private List<String> validApiKeys;

  public ApiKeysRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    String[] a = s.getAsArray("api_keys");
    if (a != null && a.length > 0) {
      validApiKeys = Lists.newArrayList();
      for (int i = 0; i < a.length; i++) {
        if (!ConfigurationHelper.isNullOrEmpty(a[i])) {
          validApiKeys.add(a[i].trim());
        }
      }
    } else {
      throw new RuleNotConfiguredException();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    String h = rc.getRequest().header("X-Api-Key");
    if (validApiKeys == null || h == null) {
      return NO_MATCH;
    }
    if (validApiKeys.contains(h)) {
      return MATCH;
    }
    return NO_MATCH;
  }
}
