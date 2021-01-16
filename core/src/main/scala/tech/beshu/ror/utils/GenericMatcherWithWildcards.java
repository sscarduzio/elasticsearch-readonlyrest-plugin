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

import java.util.*;

public class GenericMatcherWithWildcards<T> { //TODO rm this class

    private final Set<String> matchers;
    private List<String[]> patternsList = new LinkedList<>();
    private final CaseMappingEqualityJava<T> caseMappingEquality;

    public GenericMatcherWithWildcards(Iterable<String> patterns,
                                       CaseMappingEqualityJava<T> caseMappingEquality) { //todo: move from constructor
        this.caseMappingEquality = caseMappingEquality;
        this.matchers = Sets.newHashSet(patterns);
        for (String p : patterns) {
            if (p == null) {
                continue;
            }
            patternsList.add(p.split("\\*+", -1 /* want empty trailing token if any */));
        }
    }

    public Set<String> getMatchers() {
        return matchers;
    }

    public boolean match(T haystack) {
        if (haystack == null) {
            return false;
        }
        for (String[] p : patternsList) {
            if (miniglob(caseMappingEquality, p, haystack)) {
                return true;
            }
        }
        return false;
    }

    public boolean match(boolean remoteClusterAware, T haystack) {
        return remoteClusterAware ? matchRemoteClusterAware(haystack) : match(haystack);
    }

    public Set<T> filter(boolean remoteClusterAware, Set<T> haystack) {
        return remoteClusterAware ? filterRemoteClusterAware(haystack) : filter(haystack);
    }

    public boolean matchRemoteClusterAware(T haystack) {
        if (haystack == null) {
            return false;
        }

        boolean remoteClusterRequested = caseMappingEquality.show(haystack).contains(":");

        for (String[] p : patternsList) {
            // Ignore remote cluster related permissions if request didn't mean it
            if (!remoteClusterRequested && Arrays.asList(p).contains(":")) { //is it ever possible??
                continue;
            }
            if (miniglob(caseMappingEquality, p, haystack)) {
                return true;
            }
        }
        return false;
    }
    private static <T> boolean miniglob(CaseMappingEqualityJava<T> caseMappingEquality, String[] pattern, T line) {
        String showedLine = caseMappingEquality.mapCases(caseMappingEquality.show(line));
        if (pattern.length == 0) {
            return Strings.isNullOrEmpty(showedLine);
        } else if (pattern.length == 1) {
            return showedLine.equals(pattern[0]);
        }
        if (!showedLine.startsWith(caseMappingEquality.mapCases(pattern[0]))) {
            return false;
        }

        int idx = pattern[0].length();
        for (int i = 1; i < pattern.length - 1; ++i) {
            String patternTok = caseMappingEquality.mapCases(pattern[i]);
            int nextIdx = showedLine.indexOf(patternTok, idx);
            if (nextIdx < 0) {
                return false;
            } else {
                idx = nextIdx + patternTok.length();
            }
        }

        return showedLine.endsWith(caseMappingEquality.mapCases(pattern[pattern.length - 1]));
    }

    public Set<T> filter(Set<T> haystack) {
        if (haystack == null) {
            return Collections.emptySet();
        }
        Set<T> filtered = Sets.newHashSet();
        for (T hs : haystack) {
            if (match(hs)) {
                filtered.add(hs);
            }
        }
        return filtered;
    }

    public Set<T> filterRemoteClusterAware(Set<T> haystack) {
        if (haystack == null) {
            return Collections.emptySet();
        }
        Set<T> filtered = Sets.newHashSet();
        for (T hs : haystack) {
            if (matchRemoteClusterAware(hs)) {
                filtered.add(hs);
            }
        }
        return filtered;
    }
}
