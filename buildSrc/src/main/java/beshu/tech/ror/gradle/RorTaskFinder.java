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

package beshu.tech.ror.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskAction;
import org.gradle.util.internal.VersionNumber;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

public class RorTaskFinder extends DefaultTask {

  private final static VersionNumber oldestEsVersionSupported = VersionNumber.parse("6.0.0");

  public Task findRorTaskForEsVersion() {
    Project esModule = findEsModule();
    return (Task) esModule
        .getTasksByName("ror", false)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(String.format("Cannot find 'ror' task in %s module!", esModule.getName())));
  }

  @TaskAction
  public void runRorPluginBuilder() {
  }

  public Project findEsModule() {
    VersionNumber esVersionToBuild = getEsVersionToBuild();
    Optional<Project> foundEsModule = findEsModuleFor(esVersionToBuild);
    if (foundEsModule.isPresent()) {
      getLogger().info(String.format("Found es module: %s", foundEsModule.get().getName()));
      return foundEsModule.get();
    } else {
      throw new IllegalArgumentException(String.format("Cannot find ES module to build plugin for ES %s", esVersionToBuild));
    }
  }

  private VersionNumber getEsVersionToBuild() {
    String esVersion = (String) Optional
        .ofNullable(getProject().findProperty("esVersion"))
        .orElseThrow(() -> new IllegalArgumentException("No 'esVersion' property set. Cannot continue!"));
    return versionNumberFrom(esVersion);
  }

  private List<Project> getEsModules() {
    return (List<Project>) getProject()
        .getChildProjects()
        .values()
        .stream()
        .filter(this::isEsModule)
        .collect(Collectors.toList());
  }

  private boolean isEsModule(Project module) {
    return module.getName().matches("^es\\d+x$");
  }

  private Optional<Project> findEsModuleFor(VersionNumber esVersion) {
    List<Project> esModules = getEsModules();
    esModules.sort(new NewestEsVersionComparator());

    for (int i = 0; i < esModules.size(); i++) {
      VersionNumber newestEsVersionForCurrentEsModule = newestEsVersionFor(esModules.get(i));
      VersionNumber newestEsVersionForPreviousEsModule = i > 0 ? newestEsVersionFor(esModules.get(i - 1)) : oldestEsVersionSupported;

      if (isNewerThan(esVersion, newestEsVersionForPreviousEsModule) &&
          isOlderOrEqual(esVersion, newestEsVersionForCurrentEsModule)) {
        return Optional.of(esModules.get(i));
      }
    }

    return Optional.empty();
  }

  private boolean isOlderOrEqual(VersionNumber esVersion1, VersionNumber esVersion2) {
    return esVersion1.compareTo(esVersion2) <= 0;
  }

  private boolean isNewerThan(VersionNumber esVersion1, VersionNumber esVersion2) {
    return esVersion1.compareTo(esVersion2) >= 0;
  }

  private VersionNumber newestEsVersionFor(Project esModule) {
    return versionNumberFrom((String) esModule.findProperty("latestSupportedEsVersion"));
  }

  private VersionNumber versionNumberFrom(String value) {
    VersionNumber parsedEsVersion = VersionNumber.parse(value);
    if (parsedEsVersion == VersionNumber.UNKNOWN) {
      throw new IllegalArgumentException(String.format("Cannot parse %s to version number. Cannot continue!", value));
    }
    return parsedEsVersion;
  }

  private class NewestEsVersionComparator implements Comparator<Project> {
    @Override
    public int compare(Project esModule1, Project esModule2) {
      VersionNumber newestEsVersionForEsModule1 = newestEsVersionFor(esModule1);
      VersionNumber newestEsVersionForEsModule2 = newestEsVersionFor(esModule2);
      return newestEsVersionForEsModule1.compareTo(newestEsVersionForEsModule2);
    }
  }
}
