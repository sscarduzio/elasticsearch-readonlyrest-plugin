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

import java.util.List;
import java.util.stream.Collectors;

/**
 * Prints the names of all {@code esXXx} modules for one ES generation (major) to stdout, newest
 * module first. Invoke with {@code --quiet} to suppress Gradle's own output so only module names
 * come through. Pair with {@code :esXXx:printEsVersionsForModule} to get each module's versions.
 *
 * <p>Input (project property): {@code -PesMajor=<n>}.
 * Usage: {@code ./gradlew printEsModules -PesMajor=8 --quiet}
 */
public class PrintEsModulesTask extends DefaultTask {

  @TaskAction
  public void printModules() {
    int esMajor = Integer.parseInt(requiredProperty("esMajor"));

    List<Project> modules =
        EsModuleFinder.sortedEsModules(
                getProject(), EsModuleFinder.newestEsVersionComparator().reversed())
            .stream()
            .filter(module -> majorVersionOf(EsModuleFinder.newestEsVersionFor(module)) == esMajor)
            .collect(Collectors.toList());

    for (Project module : modules) {
      System.out.println(module.getName());
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
