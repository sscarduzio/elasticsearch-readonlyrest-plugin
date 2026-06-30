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

package tech.beshu.ror.gradle;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.Project;

/**
 * Parses a module's {@code supportedEsVersions} gradle property (oldest-first CSV -- the single source of
 * truth for which ES versions a module publishes) into the version list and its oldest/newest ends. Shared
 * by the readonlyrest.* convention plugins so this parsing lives in exactly one place.
 *
 * <p>The CSV must be ordered oldest-first; {@link #of} enforces this with a semver-aware check and
 * throws {@link org.gradle.api.GradleException} if the order is wrong.
 */
public final class EsVersions {

  public final List<String> all;
  public final String oldest;
  public final String newest;

  private EsVersions(List<String> all) {
    this.all = all;
    this.oldest = all.get(0);
    this.newest = all.get(all.size() - 1);
  }

  public static EsVersions of(Project project) {
    List<String> list =
        Arrays.stream(((String) project.property("supportedEsVersions")).split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    if (list.isEmpty()) {
      throw new GradleException("supportedEsVersions is empty for project " + project.getName());
    }
    List<String> sorted = list.stream()
        .sorted(EsVersionComparator.INSTANCE)
        .collect(Collectors.toList());
    if (!sorted.equals(list)) {
      throw new GradleException(
          "supportedEsVersions in " + project.getName() + " must be ordered oldest-first, but found: " + list
          + "; expected order: " + sorted);
    }
    return new EsVersions(list);
  }

  /** The ES version to DELIVER: the {@code -PesVersion} override if given, otherwise the newest supported. */
  public static String delivered(Project project) {
    return project.hasProperty("esVersion")
        ? String.valueOf(project.property("esVersion"))
        : of(project).newest;
  }

  /**
   * The ES version to COMPILE against (the base): the {@code -PbaselineEsVersion} override if given (used only
   * by the CI bytecode guard), otherwise the oldest supported.
   */
  public static String baseline(Project project) {
    return project.hasProperty("baselineEsVersion")
        ? String.valueOf(project.property("baselineEsVersion"))
        : of(project).oldest;
  }

  private static final class EsVersionComparator implements Comparator<String> {

    static final EsVersionComparator INSTANCE = new EsVersionComparator();

    private static final Pattern QUALIFIER_PATTERN = Pattern.compile("([a-zA-Z]+)(\\d+)");

    @Override
    public int compare(String a, String b) {
      String[] aParts = a.split("-", 2);
      String[] bParts = b.split("-", 2);
      int baseCompare = compareBaseVersion(aParts[0], bParts[0]);
      if (baseCompare != 0) return baseCompare;
      if (aParts.length == 1 && bParts.length == 1) return 0;
      if (aParts.length == 1) return 1;   // release sorts after pre-release
      if (bParts.length == 1) return -1;
      return compareQualifier(aParts[1], bParts[1]);
    }

    private static int compareBaseVersion(String a, String b) {
      String[] aParts = a.split("\\.", 3);
      String[] bParts = b.split("\\.", 3);
      for (int i = 0; i < 3; i++) {
        try {
          int ai = i < aParts.length ? Integer.parseInt(aParts[i]) : 0;
          int bi = i < bParts.length ? Integer.parseInt(bParts[i]) : 0;
          if (ai != bi) return Integer.compare(ai, bi);
        } catch (NumberFormatException e) {
          throw new GradleException("Cannot parse ES version segment in '" + a + "' or '" + b + "': " + e.getMessage());
        }
      }
      return 0;
    }

    private static int compareQualifier(String a, String b) {
      Matcher ma = QUALIFIER_PATTERN.matcher(a);
      Matcher mb = QUALIFIER_PATTERN.matcher(b);
      if (ma.matches() && mb.matches()) {
        int labelCmp = ma.group(1).compareTo(mb.group(1));
        if (labelCmp != 0) return labelCmp;
        return Integer.compare(Integer.parseInt(ma.group(2)), Integer.parseInt(mb.group(2)));
      }
      return a.compareTo(b);
    }
  }
}
