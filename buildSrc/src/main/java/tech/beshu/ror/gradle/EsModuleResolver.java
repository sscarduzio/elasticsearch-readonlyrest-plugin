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

import org.gradle.api.Project;
import org.gradle.util.internal.VersionNumber;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Single source of truth for mapping an ES version to the {@code esXXx} module that builds the plugin
 * for it. Each module declares the ES versions it supports via the {@code supportedEsVersions} gradle
 * property (oldest-first CSV); everything else is derived from that one list. The matching rule: ES
 * modules are sorted by their newest supported version; a version belongs to the first module whose range
 * {@code (previousModuleNewest, thisModuleNewest]} contains it (the oldest module's lower bound is
 * {@link #OLDEST_ES_VERSION_SUPPORTED}).
 */
public final class EsModuleResolver {

  public static final VersionNumber OLDEST_ES_VERSION_SUPPORTED = VersionNumber.parse("6.7.0");

  private EsModuleResolver() {
  }

  public static Optional<Project> findEsModuleFor(Project rootProject, String esVersion) {
    return findEsModuleFor(rootProject, versionNumberFrom(esVersion));
  }

  public static Optional<Project> findEsModuleFor(Project rootProject, VersionNumber esVersion) {
    List<Project> esModules = sortedEsModules(rootProject, newestEsVersionComparator());

    for (int i = 0; i < esModules.size(); i++) {
      VersionNumber newestForCurrentModule = newestEsVersionFor(esModules.get(i));
      VersionNumber newestForPreviousModule = i > 0 ? newestEsVersionFor(esModules.get(i - 1)) : OLDEST_ES_VERSION_SUPPORTED;

      if (esVersion.compareTo(newestForPreviousModule) >= 0 && esVersion.compareTo(newestForCurrentModule) <= 0) {
        return Optional.of(esModules.get(i));
      }
    }

    return Optional.empty();
  }

  public static Optional<VersionNumber> findTheNewestSupportedEsVersion(Project rootProject) {
    return sortedEsModules(rootProject, newestEsVersionComparator().reversed())
        .stream()
        .map(EsModuleResolver::newestEsVersionFor)
        .findFirst();
  }

  public static VersionNumber newestEsVersionFor(Project esModule) {
    return supportedEsVersionsFor(esModule).stream()
        .map(EsModuleResolver::versionNumberFrom)
        .max(Comparator.naturalOrder())
        .orElseThrow(() -> new IllegalArgumentException(
            String.format("Module %s has an empty 'supportedEsVersions'", esModule.getName())));
  }

  /** The ES versions a module publishes, as declared (trimmed) in its {@code supportedEsVersions} property. */
  public static List<String> supportedEsVersionsFor(Project esModule) {
    Object raw = esModule.findProperty("supportedEsVersions");
    if (raw == null || ((String) raw).isBlank()) {
      throw new IllegalArgumentException(
          String.format("Module %s is missing the 'supportedEsVersions' gradle property", esModule.getName()));
    }
    return Arrays.stream(((String) raw).split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }

  public static List<Project> sortedEsModules(Project rootProject, Comparator<Project> comparator) {
    List<Project> esModules = rootProject
        .getChildProjects()
        .values()
        .stream()
        .filter(EsModuleResolver::isEsModule)
        .collect(Collectors.toList());
    esModules.sort(comparator);
    return esModules;
  }

  public static Comparator<Project> newestEsVersionComparator() {
    return Comparator.comparing(EsModuleResolver::newestEsVersionFor);
  }

  public static VersionNumber versionNumberFrom(String value) {
    VersionNumber parsedEsVersion = VersionNumber.parse(value);
    if (parsedEsVersion == VersionNumber.UNKNOWN) {
      throw new IllegalArgumentException(String.format("Cannot parse %s to version number. Cannot continue!", value));
    }
    return parsedEsVersion;
  }

  private static boolean isEsModule(Project module) {
    return module.getName().matches("^es\\d+x$");
  }
}
