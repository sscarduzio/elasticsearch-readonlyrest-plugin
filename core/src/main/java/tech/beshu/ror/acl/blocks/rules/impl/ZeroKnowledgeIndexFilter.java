package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.collect.Sets;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;

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
  public boolean alterIndicesIfNecessaryAndCheck(Set<String> indices, MatcherWithWildcards matcher, Consumer<Set<String>> indicesWriter) {
    Set<String> modifiedIndices = alterIndicesIfNecessary(indices, matcher);
    if (modifiedIndices != null) {
      if (modifiedIndices.isEmpty()) {
        return false;
      }
      indicesWriter.accept(modifiedIndices);
    }
    return true;
  }

  public Set<String> alterIndicesIfNecessary(Set<String> indices, MatcherWithWildcards matcher) {

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
      if (matcher.match(remoteClusterAware, i)) {
        newIndices.add(i);
        continue;
      }

      MatcherWithWildcards revMatcher = new MatcherWithWildcards(Sets.newHashSet(i));
      Set<String> matched = revMatcher.filter(remoteClusterAware, matcher.getMatchers());

      if (!matched.isEmpty()) {
        newIndices.addAll(matched);
        shouldReplace = true;
      }
    }
    if (shouldReplace) {
      return newIndices;
    }
    else {
      return null;
    }
  }

}
