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

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.requestcontext.RequestContext;
import org.elasticsearch.rest.RestRequest;

import java.util.List;
import java.util.Optional;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class MethodsSyncRule extends SyncRule {
  private List<String> allowedMethods;

  public MethodsSyncRule(Settings s) throws RuleNotConfiguredException {
    super();
    String[] a = s.getAsArray(getKey());
    if (a != null && a.length > 0) {
      try {
        for (String string : a) {
          RestRequest.Method m = RestRequest.Method.valueOf(string.trim().toUpperCase());
          if (allowedMethods == null) {
            allowedMethods = Lists.newArrayList();
          }
          allowedMethods.add(m.toString());
        }
      } catch (Throwable t) {
        throw new RuleConfigurationError("Invalid HTTP method found in configuration " + a, t);
      }
    }
    else {
      throw new RuleNotConfiguredException();
    }

  }

  public static Optional<MethodsSyncRule> fromSettings(Settings s) {
    try {
      return Optional.of(new MethodsSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
      return Optional.empty();
    }
  }

  /*
    NB: Elasticsearch will parse as GET any HTTP methods than it does not understand.
    So it's normal if you allowed GET and see a 'LINK' request going throw.
    It's actually interpreted by all means as a GET!
   */
  @Override
  public RuleExitResult match(RequestContext rc) {
    if (allowedMethods.contains(rc.getMethod())) {
      return MATCH;
    }
    else {
      return NO_MATCH;
    }
  }
}
