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

package tech.beshu.ror.gradle.utils;

import org.gradle.api.GradleException;
import org.gradle.api.Project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a module's ES-version gradle properties -- the single source of truth for which ES versions a module
 * publishes and how it compiles them -- so the readonlyrest.* convention plugins share this logic in exactly
 * one place. From {@code supportedEsVersions} (an oldest-first CSV) it derives {@link #all} and its
 * {@link #oldest}/{@link #newest} ends; on top of that it resolves the version to {@link #delivered}, the
 * {@link #baseline} to compile against, and -- when a module splits its range with {@code baseEsVersions} --
 * the per-group {@link #baseVersions bases}, the {@link #baseFor base of a given version}, and the
 * {@link #groupNewest newest version of each group}.
 *
 * <p>Both CSVs must be ordered oldest-first; parsing enforces this with a semver-aware check (see
 * {@link #VERSION_COMPARATOR}) and throws {@link org.gradle.api.GradleException} if the order is wrong.
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
    List<String> list = parseVersionCsv(project, "supportedEsVersions");
    if (list.isEmpty()) {
      throw new GradleException("supportedEsVersions is empty for project " + project.getName());
    }
    requireOldestFirst(project, "supportedEsVersions", list);
    return new EsVersions(list);
  }

  /**
   * The ES version to DELIVER: the {@code -PesVersion} override when it names a version THIS module supports,
   * otherwise the newest supported. {@code -PesVersion} is a GLOBAL property, so building one module (e.g.
   * {@code :es818x:buildRorPluginZip -PesVersion=8.18.0}) sets it for every module as they configure; a
   * sibling that does not support that version must ignore it rather than adopt a foreign version. Honoring it
   * only for the owning module mirrors how {@link EsModuleFinder} routes a version to exactly the module whose
   * {@code supportedEsVersions} lists it.
   */
  public static String delivered(Project project) {
    return supportedOverride(project, "esVersion").orElseGet(() -> of(project).newest);
  }

  /**
   * The ES version to COMPILE against for the delivered version: the {@code -PbaselineEsVersion} override when
   * it names a version THIS module supports (used only by the CI bytecode guard to force a recompile at a
   * boundary version; also global, so siblings ignore a foreign value), otherwise the base of the group the
   * delivered version falls into (see {@link #baseFor}). With the single-base default this is the oldest
   * supported version, so the whole module reuses one set of base bytecode.
   */
  public static String baseline(Project project) {
    return supportedOverride(project, "baselineEsVersion")
        .orElseGet(() -> baseFor(project, delivered(project)));
  }

  /** A global {@code -P<property>} version override, honored only when THIS module supports the version. */
  private static Optional<String> supportedOverride(Project project, String property) {
    if (!project.hasProperty(property)) {
      return Optional.empty();
    }
    String version = String.valueOf(project.property(property));
    return of(project).all.contains(version) ? Optional.of(version) : Optional.empty();
  }

  /**
   * The compile bases for this module: the {@code baseEsVersions} property as an oldest-first list, or
   * {@code [oldest supported]} when unset (the single-base default). A module declares multiple bases when the
   * SAME source compiles to DIFFERENT bytecode across its ES range (an ES API changed shape mid-range, e.g.
   * es74x where RestClient.performRequestAsync gained a Cancellable return at 7.5 and BytesReference went
   * class->interface at 7.6). Each base then heads a sub-range whose versions are repackaged from it. Every
   * base must be a supported version, the list must be oldest-first, and it must start at the oldest supported
   * version so every supported version has a base at or below it.
   */
  public static List<String> baseVersions(Project project) {
    EsVersions v = of(project);
    if (!project.hasProperty("baseEsVersions")) {
      return List.of(v.oldest);
    }
    List<String> bases = parseVersionCsv(project, "baseEsVersions");
    if (bases.isEmpty()) {
      throw new GradleException("baseEsVersions is empty for project " + project.getName());
    }
    for (String b : bases) {
      if (!v.all.contains(b)) {
        throw new GradleException(
            "baseEsVersions entry "
                + b
                + " in "
                + project.getName()
                + " is not a supported ES version; supportedEsVersions="
                + v.all);
      }
    }
    requireOldestFirst(project, "baseEsVersions", bases);
    if (!bases.get(0).equals(v.oldest)) {
      throw new GradleException(
          "baseEsVersions in "
              + project.getName()
              + " must start at the oldest supported version ("
              + v.oldest
              + ") so every version has a base; found first base "
              + bases.get(0));
    }
    return bases;
  }

  /** The compile base for {@code esVersion}: the greatest declared base version that is {@code <=} it. */
  public static String baseFor(Project project, String esVersion) {
    List<String> bases = baseVersions(project);
    String chosen = null;
    for (String b : bases) {
      if (VERSION_COMPARATOR.compare(b, esVersion) <= 0) {
        chosen = b;
      } else {
        break;
      }
    }
    if (chosen == null) {
      throw new GradleException(
          "No base ES version <= "
              + esVersion
              + " in "
              + project.getName()
              + "; baseEsVersions="
              + bases);
    }
    return chosen;
  }

  /**
   * The newest supported version in each base group: for each base, the greatest supported version that maps to
   * it via {@link #baseFor}. These are the farthest-from-base versions, so verifying their repackage is what
   * proves each group safe. For the single-base default this is just {@code [newest]}.
   */
  public static List<String> groupNewest(Project project) {
    EsVersions v = of(project);
    List<String> bases = baseVersions(project);
    List<String> result = new ArrayList<>();
    for (int i = 0; i < bases.size(); i++) {
      String base = bases.get(i);
      String nextBase = (i + 1 < bases.size()) ? bases.get(i + 1) : null;
      String groupNewest = base; // at minimum the base itself
      for (String ver : v.all) { // oldest-first, so the last match is the group's newest
        boolean atOrAboveBase = VERSION_COMPARATOR.compare(ver, base) >= 0;
        boolean belowNextBase = nextBase == null || VERSION_COMPARATOR.compare(ver, nextBase) < 0;
        if (atOrAboveBase && belowNextBase) {
          groupNewest = ver;
        }
      }
      result.add(groupNewest);
    }
    return result;
  }

  /** Parses a required comma-separated version property into a trimmed, non-empty list. */
  private static List<String> parseVersionCsv(Project project, String property) {
    return Arrays.stream(String.valueOf(project.property(property)).split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  /** Throws if {@code versions} is not already in oldest-first (semver-aware) order. */
  private static void requireOldestFirst(Project project, String property, List<String> versions) {
    List<String> sorted = versions.stream().sorted(VERSION_COMPARATOR).toList();
    if (!sorted.equals(versions)) {
      throw new GradleException(
          property
              + " in "
              + project.getName()
              + " must be ordered oldest-first, but found: "
              + versions
              + "; expected order: "
              + sorted);
    }
  }

  public static final Comparator<String> VERSION_COMPARATOR = EsVersionComparator.INSTANCE;

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
      if (aParts.length == 1) return 1; // release sorts after pre-release
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
          throw new GradleException(
              "Cannot parse ES version segment in '" + a + "' or '" + b + "': " + e.getMessage());
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
