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

  public static List<Project> sortedEsModules(Project rootProject, Comparator<Project> comparator) {
    List<Project> esModules = allEsModules(rootProject);
    esModules.sort(comparator);
    return esModules;
  }

  public static Comparator<Project> newestEsVersionComparator() {
    return Comparator.comparing(EsModuleFinder::newestEsVersionFor, EsVersions.VERSION_COMPARATOR);
  }

  private static List<Project> allEsModules(Project rootProject) {
    return rootProject.getChildProjects().values().stream()
        .filter(module -> module.getName().matches("^es\\d+x$"))
        .collect(Collectors.toList());
  }
}
