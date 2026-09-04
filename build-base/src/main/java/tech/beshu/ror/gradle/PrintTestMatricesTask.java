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
import org.gradle.api.tasks.TaskAction;
import tech.beshu.ror.gradle.utils.TestMatrixPolicy;
import tech.beshu.ror.gradle.utils.TestMatrixPolicy.Selection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds every test matrix of ci.yml from the modules that exist: printed as {@code name=[...]} for
 * humans, AND written to {@code build/ci-matrices/<name>.json} for the workflow. Read the files.
 * Configuration-time logging can pollute stdout even under {@code --quiet}.
 *
 * <p>The workflow decides which matrix a run takes, because only it knows the branch and the event.
 * This task decides what each matrix holds. See "Test matrix policy" in ci/CI.md.
 *
 * <p>Usage: {@code ./gradlew printTestMatrices --quiet}
 */
public class PrintTestMatricesTask extends DefaultTask {

  /**
   * ROR supports no Windows or Kibana stack on ES 6, so those two families skip it. The Linux
   * integration tests still cover it.
   */
  private static final Set<Integer> NO_WINDOWS_OR_E2E = Collections.singleton(6);

  private static final Set<Integer> EVERY_MAJOR = Collections.emptySet();

  @TaskAction
  public void printMatrices() {
    Map<String, List<String>> matrices = new LinkedHashMap<>();
    matrices.put("linux_it_full", modulesFor(Selection.ALL, EVERY_MAJOR));
    matrices.put("linux_it_pr_ready", modulesFor(Selection.READY_PR, EVERY_MAJOR));
    matrices.put("linux_it_pr_draft", modulesFor(Selection.NEWEST, EVERY_MAJOR));
    matrices.put("win_it_full", modulesFor(Selection.ALL, NO_WINDOWS_OR_E2E));
    matrices.put(
        "win_it_master_or_develop", modulesFor(Selection.OLDEST_AND_NEWEST, NO_WINDOWS_OR_E2E));
    matrices.put("win_it_pr_ready", modulesFor(Selection.NEWEST, NO_WINDOWS_OR_E2E));
    matrices.put("e2e_full", modulesFor(Selection.NEWEST, NO_WINDOWS_OR_E2E));

    matrices.forEach(
        (name, modules) -> {
          String json = asJsonArray(modules);
          System.out.println(name + "=" + json);
          writeMatrixFile(name, json);
        });
  }

  private List<String> modulesFor(Selection selection, Set<Integer> skippedMajors) {
    return TestMatrixPolicy.modulesFor(getProject(), selection, skippedMajors);
  }

  private static String asJsonArray(List<String> modules) {
    return modules.stream().collect(Collectors.joining("\",\"", "[\"", "\"]"));
  }

  private void writeMatrixFile(String name, String json) {
    Path outputFile =
        getProject()
            .getLayout()
            .getBuildDirectory()
            .file("ci-matrices/" + name + ".json")
            .get()
            .getAsFile()
            .toPath();
    try {
      Files.createDirectories(outputFile.getParent());
      Files.write(outputFile, Collections.singletonList(json));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write " + outputFile, e);
    }
  }
}
