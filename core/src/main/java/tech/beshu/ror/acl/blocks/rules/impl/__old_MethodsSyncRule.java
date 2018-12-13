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

package tech.beshu.ror.acl.blocks.rules.impl;

import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.httpclient.HttpMethod;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.MethodsRuleSettings;

import java.util.Set;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class __old_MethodsSyncRule extends SyncRule {

  private final Set<HttpMethod> allowedMethods;
  private final MethodsRuleSettings settings;

  public __old_MethodsSyncRule(MethodsRuleSettings s) {
    this.allowedMethods = s.getMethods();
    this.settings = s;
  }

  /*
    NB: Elasticsearch will parse as GET any HTTP methods that it does not understand.
    So it's normal if you allowed GET and see a 'LINK' request going throw.
    It's actually interpreted by all means as a GET!
   */
  @Override
  public RuleExitResult match(__old_RequestContext rc) {
    return allowedMethods.contains(rc.getMethod())
      ? MATCH
      : NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}
