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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Emits the authoritative ES-version -&gt; module mapping used by the publishing pipeline to group a
 * generation's versions by the {@code esXXx} module that builds them (so each module is compiled once
 * and the remaining versions are repackaged from that base). Reuses {@link EsModuleResolver}, the same
 * range-matching logic the per-version build dispatch ({@link RorTaskFinder}) uses.
 *
 * <p>Inputs (project properties): {@code -PversionsFile=<path>} (one ES version per line; blanks and
 * {@code #} comments ignored) and {@code -PoutputFile=<path>}. Output: one line per version,
 * {@code <esVersion> <moduleName> <moduleLatestSupportedEsVersion>}.
 */
public class PrintEsModuleMapTask extends DefaultTask {

  @TaskAction
  public void printMap() throws IOException {
    String versionsFile = requiredProperty("versionsFile");
    String outputFile = requiredProperty("outputFile");

    List<String> versions = Files.readAllLines(Path.of(versionsFile), StandardCharsets.UTF_8)
        .stream()
        .map(String::trim)
        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
        .collect(Collectors.toList());

    StringBuilder mapping = new StringBuilder();
    for (String esVersion : versions) {
      Project module = EsModuleResolver.findEsModuleFor(getProject(), esVersion)
          .orElseThrow(() -> new IllegalArgumentException(String.format("Cannot find ES module to build plugin for ES %s", esVersion)));
      String moduleLatest = (String) module.findProperty("latestSupportedEsVersion");
      mapping.append(esVersion).append(' ').append(module.getName()).append(' ').append(moduleLatest).append('\n');
    }

    Files.writeString(Path.of(outputFile), mapping.toString(), StandardCharsets.UTF_8);
    getLogger().lifecycle("Wrote ES module map for {} versions to {}", versions.size(), outputFile);
  }

  private String requiredProperty(String name) {
    Object value = getProject().findProperty(name);
    if (value == null) {
      throw new IllegalArgumentException(String.format("Missing required '-P%s=<path>' property", name));
    }
    return (String) value;
  }
}
