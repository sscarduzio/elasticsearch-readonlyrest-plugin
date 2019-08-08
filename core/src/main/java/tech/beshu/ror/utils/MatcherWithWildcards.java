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


import com.google.common.base.Strings;
import com.google.common.collect.Sets;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class MatcherWithWildcards {
  private static Set<String> empty = new HashSet<>(0);
  private final Set<String> matchers;
  private List<String[]> patternsList = new LinkedList<>();

  public MatcherWithWildcards(Iterable<String> patterns) {
    this.matchers = Sets.newHashSet(patterns);
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

  public boolean match(boolean remoteClusterAware, String haystack) {
    return remoteClusterAware ? matchRemoteClusterAware(haystack) : match(haystack);
  }

  public Set<String> filter(boolean remoteClusterAware, Set<String> haystack) {
    return remoteClusterAware ? filterRemoteClusterAware(haystack) : filter(haystack);
  }

  public boolean matchRemoteClusterAware(String haystack) {
    if (haystack == null) {
      return false;
    }

    boolean remoteClusterRequested = haystack.contains(":");

    for (String[] p : patternsList) {
      // Ignore remote cluster related permissions if request didn't mean it
      if (!remoteClusterRequested && Arrays.asList(p).contains(":")) {
        continue;
      }
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
  
  public Set<String> matchingMatchers(Set<String> haystack) {
    if (haystack == null) {
      return empty;
    }
    Set<String> filtered = Sets.newHashSet();
    for (String hs : haystack) {
      for (String m : matchers) {
        if (new MatcherWithWildcards(Sets.newHashSet(m)).match(hs)) {
          filtered.add(m);
        }
      }
    }
    return filtered;
}

  public Set<String> filterRemoteClusterAware(Set<String> haystack) {
    if (haystack == null) {
      return empty;
    }
    Set<String> filtered = Sets.newHashSet();
    for (String hs : haystack) {
      if (matchRemoteClusterAware(hs)) {
        filtered.add(hs);
      }
    }
    return filtered;
  }
}
