package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.rest.RestRequest;

import java.util.List;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class MethodsRule extends Rule {
  private List<RestRequest.Method> allowedMethods;

  public MethodsRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    String[] a = s.getAsArray(KEY);
    if (a != null && a.length > 0) {
      try {
        for (String string : a) {
          RestRequest.Method m = RestRequest.Method.valueOf(string.trim().toUpperCase());
          if (allowedMethods == null) {
            allowedMethods = Lists.newArrayList();
          }
          allowedMethods.add(m);
        }
      } catch (Throwable t) {
        throw new RuleConfigurationError("Invalid HTTP method found in configuration " + a, t);
      }
    } else {
      throw new RuleNotConfiguredException();
    }

  }

  /*
    NB: Elasticsearch will parse as GET any HTTP methods than it does not understand.
    So it's normal if you allowed GET and see a 'LINK' request going throw.
    It's actually interpreted by all means as a GET!
   */
  @Override
  public RuleExitResult match(RequestContext rc) {
    if(allowedMethods.contains(rc.getRequest().method())) {
     return MATCH;
    }
    else {
      return NO_MATCH;
    }
  }
}
