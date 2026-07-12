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
import tech.beshu.ror.gradle.utils.EsModuleFinder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lists the names of all {@code esXXx} modules for one ES generation (major), newest module
 * first: printed to stdout for humans, AND written to {@code build/es-modules/es<major>x.txt}
 * (one per line) for scripts. Scripts must read the file — never parse the stdout, which any
 * configuration-time build-script logging can pollute even under {@code --quiet}.
 * Pair with {@code :esXXx:printEsVersionsForModule} to get each module's versions.
 *
 * <p>Input (project property): {@code -PesMajor=<n>}.
 * Usage: {@code ./gradlew printEsModules -PesMajor=8}
 */
public class PrintEsModulesTask extends DefaultTask {

  @TaskAction
  public void printModules() {
    int esMajor = Integer.parseInt(requiredProperty("esMajor"));

    List<String> moduleNames =
        EsModuleFinder.sortedEsModules(
                getProject(), EsModuleFinder.newestEsVersionComparator().reversed())
            .stream()
            .filter(module -> majorVersionOf(EsModuleFinder.newestEsVersionFor(module)) == esMajor)
            .map(Project::getName)
            .collect(Collectors.toList());

    moduleNames.forEach(System.out::println);
    writeModulesFile(esMajor, moduleNames);
  }

  private void writeModulesFile(int esMajor, List<String> moduleNames) {
    Path outputFile =
        getProject()
            .getLayout()
            .getBuildDirectory()
            .file("es-modules/es" + esMajor + "x.txt")
            .get()
            .getAsFile()
            .toPath();
    try {
      Files.createDirectories(outputFile.getParent());
      Files.write(outputFile, moduleNames);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write " + outputFile, e);
    }
  }

  private static int majorVersionOf(String version) {
    try {
      return Integer.parseInt(version.split("\\.")[0]);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Cannot parse major version from: " + version, e);
    }
  }

  private String requiredProperty(String name) {
    Object value = getProject().findProperty(name);
    if (value == null) {
      throw new IllegalArgumentException(
          String.format("Missing required '-P%s=<value>' property", name));
    }
    return (String) value;
  }
}
