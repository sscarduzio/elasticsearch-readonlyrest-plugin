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

import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ZeroKnowledgeIndexFilter {

  private final boolean remoteClusterAware;

  public ZeroKnowledgeIndexFilter(boolean remoteClusterAware) {
    this.remoteClusterAware = remoteClusterAware;
  }

  /**
   * Transform the indices list of an incoming request at best effort, without knowing the complete list of available indices and aliases.
   *
   * @param indices       indices in the request.
   * @param matcher       indices rules matcher.
   * @param indicesWriter function to write indices in request.
   * @return can be allowed
   */
  public boolean alterIndicesIfNecessaryAndCheck(Set<String> indices, StringMatcherWithWildcards matcher, Consumer<Set<String>> indicesWriter) {
    Set<String> modifiedIndices = alterIndicesIfNecessary(indices, matcher);
    if (modifiedIndices != null) {
      if (modifiedIndices.isEmpty()) {
        return false;
      }
      indicesWriter.accept(modifiedIndices);
    } else {
      indicesWriter.accept(indices);
    }
    return true;
  }

  private Set<String> alterIndicesIfNecessary(Set<String> indices, StringMatcherWithWildcards matcher) {

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
      if (!remoteClusterAware) {
        return matcher.getMatchers();
      }
      if (indices.size() == 1) {
        return matcher.getMatchers().stream().filter(m -> !m.contains(":")).collect(Collectors.toSet());
      }
      else {
        shouldReplace = true;
        indices.remove("*");
        indices.addAll(matcher.getMatchers().stream().filter(m -> !m.contains(":")).collect(Collectors.toSet()));
      }
    }

    Set<String> newIndices = Sets.newHashSet();
    for (String i : indices) {
      if (matcher.match(i)) {
        newIndices.add(i);
        continue;
      }

      StringMatcherWithWildcards revMatcher = new StringMatcherWithWildcards(Sets.newHashSet(i));
      Set<String> matched = revMatcher.filter(matcher.getMatchers());

      if (!matched.isEmpty()) {
        newIndices.addAll(matched);
        shouldReplace = true;
      }
    }
    if (shouldReplace || ! Sets.symmetricDifference(newIndices,indices).isEmpty()) {
      return newIndices;
    }
    else {
      return indices;
    }
  }

}
