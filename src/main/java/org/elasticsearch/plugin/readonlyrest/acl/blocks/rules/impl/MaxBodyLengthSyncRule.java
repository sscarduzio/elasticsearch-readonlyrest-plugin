/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class MaxBodyLengthSyncRule extends SyncRule {
  private Integer maxBodyLength;

  public MaxBodyLengthSyncRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    maxBodyLength = s.getAsInt("maxBodyLength", null);
    if (maxBodyLength == null) {
      throw new RuleNotConfiguredException();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    return (rc.getContent().length() > maxBodyLength) ? NO_MATCH : MATCH;
  }
}
