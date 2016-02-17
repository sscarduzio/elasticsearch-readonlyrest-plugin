package org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.util.List;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class MethodsRule extends Rule {
  private List<RestRequest.Method> allowedMethods;

  public MethodsRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    String[] a = s.getAsArray("methods");
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

  @Override
  public RuleExitResult match(RestRequest request, RestChannel channel) {
    if(allowedMethods.contains(request.method())) {
     return MATCH;
    }
    else {
      return NO_MATCH;
    }
  }
}
