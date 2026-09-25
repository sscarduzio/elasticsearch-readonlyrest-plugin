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

import org.gradle.api.Project;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds the {@code esXXx} module responsible for a given ES version by checking whether the version
 * is explicitly listed in the module's {@code supportedEsVersions} gradle property (oldest-first CSV).
 */
public final class EsModuleFinder {

  private EsModuleFinder() {}

  public static Optional<Project> findEsModuleFor(Project rootProject, String esVersion) {
    return allEsModules(rootProject).stream()
        .filter(module -> EsVersions.of(module).all.contains(esVersion))
        .findFirst();
  }

  public static Optional<String> findTheNewestSupportedEsVersion(Project rootProject) {
    return allEsModules(rootProject).stream()
        .map(EsModuleFinder::newestEsVersionFor)
        .max(EsVersions.VERSION_COMPARATOR);
  }

  public static String newestEsVersionFor(Project esModule) {
    return EsVersions.of(esModule).newest;
  }

  /** Every ES version ROR supports, oldest first, from each module's {@code supportedEsVersions}. */
  public static List<String> allSupportedEsVersions(Project rootProject) {
    return esModulesSortedBy(rootProject, newestEsVersionComparator())
        .flatMap(module -> EsVersions.of(module).all.stream())
        .distinct()
        .collect(Collectors.toList());
  }

  /**
   * Every ES major ROR builds, newest first. A module counts for the major of its NEWEST supported
   * version, the rule {@code printEsModules} uses, so a module that spans two majors counts once.
   *
   * <p>CI builds its matrices from this list, which a hand-written one would drift from.
   */
  public static List<Integer> allSupportedEsMajors(Project rootProject) {
    return allEsModules(rootProject).stream()
        .map(EsModuleFinder::majorOf)
        .distinct()
        .sorted(Comparator.reverseOrder())
        .collect(Collectors.toList());
  }

  /**
   * The newest ES module: the one whose newest supported version is the highest. A caller that
   * needs ONE module asks here — a unit-test run, or a tool that reads a plugin descriptor. An
   * integration-test leg instead names the module it covers.
   *
   * <p>Derived from {@code supportedEsVersions}, so a new module needs no edit.
   */
  public static String newestEsModuleName(Project rootProject) {
    return esModulesSortedBy(rootProject, newestEsVersionComparator().reversed())
        .map(Project::getName)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("The build holds no esXXx module"));
  }

  /**
   * The modules of one ES major, newest first. A module counts for the major of its newest supported
   * version, so a module that spans two majors appears under one of them only.
   */
  public static List<String> esModuleNamesForMajor(Project rootProject, int esMajor) {
    return esModulesSortedBy(rootProject, newestEsVersionComparator().reversed())
        .filter(module -> majorOf(module) == esMajor)
        .map(Project::getName)
        .collect(Collectors.toList());
  }

  /** The major part of an ES version. For {@code 10.0.1} this is 10. */
  public static int majorVersionOf(String version) {
    try {
      return Integer.parseInt(version.split("\\.")[0]);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Cannot parse major version from: " + version, e);
    }
  }

  /** A module counts for the major of its newest supported version. */
  private static int majorOf(Project esModule) {
    return majorVersionOf(newestEsVersionFor(esModule));
  }

  /**
   * Sorts a copy, never the list {@link #allEsModules} returns: {@code Collectors.toList()} does
   * not promise a mutable list.
   */
  private static Stream<Project> esModulesSortedBy(
      Project rootProject, Comparator<Project> comparator) {
    return allEsModules(rootProject).stream().sorted(comparator);
  }

  private static Comparator<Project> newestEsVersionComparator() {
    return Comparator.comparing(EsModuleFinder::newestEsVersionFor, EsVersions.VERSION_COMPARATOR);
  }

  private static List<Project> allEsModules(Project rootProject) {
    return rootProject.getChildProjects().values().stream()
        .filter(module -> module.getName().matches("^es\\d+x$"))
        .collect(Collectors.toList());
  }
}
