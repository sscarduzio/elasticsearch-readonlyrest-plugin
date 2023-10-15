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

package tech.beshu.ror.utils;

import com.google.common.collect.Sets;

import java.util.HashSet;
import java.util.Set;

public class ZeroKnowledgeMatchFilter {

  static public Set<String> alterIndicesIfNecessary(Set<String> indices, JavaStringMatcher matcher) {

    boolean shouldReplace = false;

    indices = Sets.newHashSet(indices);
    if (indices.contains("_all")) {
      indices.remove("_all");
      indices.add("*");
    }
    if (indices.size() == 0) {
      indices.add("*");
    }

    if (indices.contains("*")) {

      if (indices.size() == 1) {
        return new HashSet<>(matcher.getPatterns());
      }
      else {
        shouldReplace = true;
        indices.remove("*");
        indices.addAll(new HashSet<>(matcher.getPatterns()));
      }
    }

    Set<String> newIndices = Sets.newHashSet();
    for (String i : indices) {
      if (matcher.match(i)) {
        newIndices.add(i);
        continue;
      }

      JavaStringMatcher revMatcher = new JavaStringMatcher(Sets.newHashSet(i), matcher.getCaseSensitivity());
      Set<String> matched = revMatcher.filter(matcher.getPatterns());

      if (!matched.isEmpty()) {
        newIndices.addAll(matched);
        shouldReplace = true;
      }
    }
    if (shouldReplace || !Sets.symmetricDifference(newIndices, indices).isEmpty()) {
      return newIndices;
    }
    else {
      // This means you don't need to replace at all.
      return null;
    }
  }

}