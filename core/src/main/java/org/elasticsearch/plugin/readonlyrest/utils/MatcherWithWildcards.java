package org.elasticsearch.plugin.readonlyrest.utils;

import com.google.common.base.Strings;
import com.google.common.collect.Sets;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class MatcherWithWildcards {
  private static Set<String> empty = new HashSet<>(0);
  private final Set<String> matchers;
  private List<String[]> patternsList = new LinkedList<>();

  public MatcherWithWildcards(Set<String> patterns) {
    this.matchers = patterns;
    for (String p : patterns) {
      if (p == null) {
        continue;
      }
      patternsList.add(p.split("\\*+", -1 /* want empty trailing token if any */));
    }
  }

  private static boolean miniglob(String[] pattern, String line) {
    if (pattern.length == 0) {
      return Strings.isNullOrEmpty(line);
    }
    else if (pattern.length == 1) {
      return line.equals(pattern[0]);
    }
    if (!line.startsWith(pattern[0])) {
      return false;
    }

    int idx = pattern[0].length();
    for (int i = 1; i < pattern.length - 1; ++i) {
      String patternTok = pattern[i];
      int nextIdx = line.indexOf(patternTok, idx);
      if (nextIdx < 0) {
        return false;
      }
      else {
        idx = nextIdx + patternTok.length();
      }
    }

    return line.endsWith(pattern[pattern.length - 1]);
  }

  public Set<String> getMatchers() {
    return matchers;
  }

  public boolean match(String haystack) {
    if (haystack == null) {
      return false;
    }
    for (String[] p : patternsList) {
      if (miniglob(p, haystack)) {
        return true;
      }
    }
    return false;
  }

  public Set<String> filter(Set<String> haystack) {
    if (haystack == null) {
      return empty;
    }
    Set<String> filtered = Sets.newHashSet();
    for (String hs : haystack) {
      if (match(hs)) {
        filtered.add(hs);
      }
    }
    return filtered;
  }
}
