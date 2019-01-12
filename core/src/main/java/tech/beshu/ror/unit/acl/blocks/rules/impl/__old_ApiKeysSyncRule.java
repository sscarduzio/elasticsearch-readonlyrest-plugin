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

package tech.beshu.ror.unit.acl.blocks.rules.impl;

import tech.beshu.ror.unit.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.unit.acl.blocks.rules.SyncRule;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.__old_ApiKeysRuleSettings;

import java.util.Set;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class __old_ApiKeysSyncRule extends SyncRule {

  private final Set<String> validApiKeys;
  private final __old_ApiKeysRuleSettings settings;

  public __old_ApiKeysSyncRule(__old_ApiKeysRuleSettings s) {
    this.settings = s;
    this.validApiKeys = s.getApiKeys();
  }

  @Override
  public RuleExitResult match(__old_RequestContext rc) {
    String h = rc.getHeaders().get("X-Api-Key");
    if (validApiKeys == null || h == null) {
      return NO_MATCH;
    }
    if (validApiKeys.contains(h)) {
      return MATCH;
    }
    return NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}
