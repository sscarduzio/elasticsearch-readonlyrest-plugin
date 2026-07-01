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

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskAction;
import tech.beshu.ror.gradle.utils.EsModuleFinder;

import java.util.Optional;

public class RorTaskFinder extends DefaultTask {

  @TaskAction
  public void runRorPluginBuilder() {}

  public Task findRorTaskForEsVersion(String taskName) {
    Project esModule = findEsModuleForEsVersionToBuild();
    return (Task)
        esModule.getTasksByName(taskName, false).stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "Cannot find '%s' task in %s module!", taskName, esModule.getName())));
  }

  private Project findEsModuleForEsVersionToBuild() {
    String esVersion = getEsVersionToBuild();
    Optional<Project> foundEsModule = EsModuleFinder.findEsModuleFor(getProject(), esVersion);
    if (foundEsModule.isPresent()) {
      getLogger().info(String.format("Found es module: %s", foundEsModule.get().getName()));
      return foundEsModule.get();
    } else {
      throw new IllegalArgumentException(
          String.format("Cannot find ES module to build plugin for ES %s", esVersion));
    }
  }

  private String getEsVersionToBuild() {
    Optional<String> esVersionStr =
        Optional.ofNullable((String) getProject().findProperty("esVersion"));
    if (esVersionStr.isPresent()) {
      String version = esVersionStr.get();
      if (!version.matches("\\d+\\.\\d+\\.\\d+(-[a-zA-Z]+\\d*)?")) {
        throw new IllegalArgumentException(
            "Cannot parse '" + version + "' to ES version number. Cannot continue!");
      }
      return version;
    }
    Optional<String> newestSupportedVersion =
        EsModuleFinder.findTheNewestSupportedEsVersion(getProject());
    if (newestSupportedVersion.isPresent()) {
      getLogger()
          .warn(
              "NO 'esVersion' was explicitly set!!! Using the newest found one: {}",
              newestSupportedVersion.get());
      return newestSupportedVersion.get();
    }
    throw new IllegalArgumentException("No 'esVersion' property set. Cannot continue!");
  }
}
