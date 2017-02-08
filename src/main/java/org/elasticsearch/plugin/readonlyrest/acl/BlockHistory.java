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

package org.elasticsearch.plugin.readonlyrest.acl;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;

import java.util.Map;
import java.util.Set;

/**
 * Created by sscarduzio on 20/01/2017.
 */

class BlockHistory {
  private final Set<RuleExitResult> results;
  private final String name;

  BlockHistory(String name, Set<RuleExitResult> results) {
    this.results = results;
    this.name = name;
  }

  @Override
  public String toString() {
    Map<String, Boolean> rule2result = Maps.newHashMap();
    for (RuleExitResult rer : results) {
      rule2result.put(rer.getCondition().getKey(), rer.isMatch());
    }
    Joiner.MapJoiner j = Joiner.on(", ").withKeyValueSeparator("->");
    return "[" + name + "->[" + j.join(rule2result) + "]]";
  }
}
