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
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.MaxBodyLengthRuleSettings;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class MaxBodyLengthSyncRule extends SyncRule {

  private final Integer maxBodyLength;
  private final MaxBodyLengthRuleSettings settings;

  public MaxBodyLengthSyncRule(MaxBodyLengthRuleSettings s) {
    this.maxBodyLength = s.getMaxBodyLength();
    this.settings = s;
  }

  @Override
  public RuleExitResult match(__old_RequestContext rc) {
    return (rc.getContentLength() > maxBodyLength) ? NO_MATCH : MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}
