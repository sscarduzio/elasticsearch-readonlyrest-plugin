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
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Emits the per-module ES-version groups for one ES generation (major), used by the publishing pipeline.
 * Each {@code esXXx} module owns the versions it publishes via its {@code supportedEsVersions} gradle
 * property (the single source of truth), so this task just selects the modules in the requested major and
 * prints their lists -- there is no central per-generation version file.
 *
 * <p>Inputs (project properties): {@code -PesMajor=<n>} and {@code -PoutputFile=<path>}. Output: one line
 * per module (newest module first), {@code <moduleName> <esVersion> [<esVersion> ...]} (newest version
 * first), which the pipeline consumes to build each module once and repackage the rest.
 */
public class PrintEsModuleGroupsTask extends DefaultTask {

  @TaskAction
  public void printGroups() throws IOException {
    int esMajor = Integer.parseInt(requiredProperty("esMajor"));
    String outputFile = requiredProperty("outputFile");

    List<Project> modules = EsModuleResolver
        .sortedEsModules(getProject(), EsModuleResolver.newestEsVersionComparator().reversed())
        .stream()
        .filter(module -> EsModuleResolver.newestEsVersionFor(module).getMajor() == esMajor)
        .collect(Collectors.toList());

    StringBuilder mapping = new StringBuilder();
    for (Project module : modules) {
      List<String> versions = EsModuleResolver.supportedEsVersionsFor(module).stream()
          .sorted(Comparator.comparing(EsModuleResolver::versionNumberFrom).reversed())
          .collect(Collectors.toList());
      mapping.append(module.getName()).append(' ').append(String.join(" ", versions)).append('\n');
    }

    Files.writeString(Path.of(outputFile), mapping.toString(), StandardCharsets.UTF_8);
    getLogger().lifecycle("Wrote {} module group(s) for ES {}.x to {}", modules.size(), esMajor, outputFile);
  }

  private String requiredProperty(String name) {
    Object value = getProject().findProperty(name);
    if (value == null) {
      throw new IllegalArgumentException(String.format("Missing required '-P%s=<value>' property", name));
    }
    return (String) value;
  }
}
