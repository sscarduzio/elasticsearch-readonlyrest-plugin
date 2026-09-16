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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

class PrintTestMatricesTaskTest {

  // --- asJsonArray ---

  @Test
  void anEmptyListGivesAnEmptyArray() {
    assertEquals("[]", PrintTestMatricesTask.asJsonArray(Collections.emptyList()));
  }

  @Test
  void oneModuleGivesOneElement() {
    assertEquals(
        "[\"es94x\"]", PrintTestMatricesTask.asJsonArray(Collections.singletonList("es94x")));
  }

  @Test
  void severalModulesKeepTheirOrder() {
    assertEquals(
        "[\"es94x\",\"es90x\",\"es818x\"]",
        PrintTestMatricesTask.asJsonArray(Arrays.asList("es94x", "es90x", "es818x")));
  }

  // --- the name -> selection table ---
  //
  // A swapped line ships a draft PR that runs the full Linux matrix, and nothing reports it.

  @Test
  void everyMatrixTakesItsOwnSelection() {
    Map<String, List<String>> matrices = PrintTestMatricesTask.matricesFor(threeMajors());

    assertEquals(
        List.of(
            "es99x", "es98x", "es97x", "es96x", "es95x", "es94x", "es93x", "es92x", "es91x",
            "es90x", "es82x", "es81x", "es80x", "es61x", "es60x"),
        matrices.get("linux_it_full"));
    assertEquals(
        List.of("es99x", "es94x", "es90x", "es82x", "es80x", "es61x", "es60x"),
        matrices.get("linux_it_pr_ready"));
    assertEquals(List.of("es99x", "es82x", "es61x"), matrices.get("linux_it_pr_draft"));

    assertEquals(
        List.of(
            "es99x", "es98x", "es97x", "es96x", "es95x", "es94x", "es93x", "es92x", "es91x",
            "es90x", "es82x", "es81x", "es80x"),
        matrices.get("win_it_full"));
    assertEquals(
        List.of("es99x", "es90x", "es82x", "es80x"), matrices.get("win_it_master_or_develop"));
    assertEquals(List.of("es99x", "es82x"), matrices.get("win_it_pr_ready"));

    assertEquals(List.of("es99x", "es82x"), matrices.get("e2e_full"));
  }

  @Test
  void onlyTheLinuxMatricesCoverEs6() {
    PrintTestMatricesTask.matricesFor(threeMajors())
        .forEach(
            (name, modules) -> {
              boolean coversEs6 = modules.stream().anyMatch(module -> module.startsWith("es6"));
              assertEquals(name.startsWith("linux_"), coversEs6, name + " and ES 6");
            });
  }

  @Test
  void theTableHoldsTheSevenNamesTheWorkflowReads() {
    assertEquals(
        List.of(
            "linux_it_full",
            "linux_it_pr_ready",
            "linux_it_pr_draft",
            "win_it_full",
            "win_it_master_or_develop",
            "win_it_pr_ready",
            "e2e_full"),
        List.copyOf(PrintTestMatricesTask.matricesFor(threeMajors()).keySet()));
  }

  // --- the files ci.yml reads ---

  @Test
  void theTaskWritesOneJsonFilePerMatrix() throws Exception {
    Project root = threeMajors();
    PrintTestMatricesTask task =
        root.getTasks().create("printTestMatricesUnderTest", PrintTestMatricesTask.class);

    task.printMatrices();

    Path dir =
        root.getLayout().getBuildDirectory().get().getAsFile().toPath().resolve("ci-matrices");
    Map<String, List<String>> matrices = PrintTestMatricesTask.matricesFor(root);
    for (Map.Entry<String, List<String>> matrix : matrices.entrySet()) {
      Path file = dir.resolve(matrix.getKey() + ".json");
      assertTrue(Files.exists(file), file + " is missing");
      assertEquals(
          PrintTestMatricesTask.asJsonArray(matrix.getValue()),
          Files.readString(file).strip(),
          matrix.getKey());
    }
  }

  // ES 9 holds ten modules, the size at which READY_PR adds a middle module. Below that it gives
  // the same answer as OLDEST_AND_NEWEST, and a swap of the two would pass unseen. ES 6 shows which
  // matrices skip a major.
  private static Project threeMajors() {
    Project root = ProjectBuilder.builder().build();
    addModule(root, "es90x", "9.0.0");
    addModule(root, "es91x", "9.1.0");
    addModule(root, "es92x", "9.2.0");
    addModule(root, "es93x", "9.3.0");
    addModule(root, "es94x", "9.4.0");
    addModule(root, "es95x", "9.5.0");
    addModule(root, "es96x", "9.6.0");
    addModule(root, "es97x", "9.7.0");
    addModule(root, "es98x", "9.8.0");
    addModule(root, "es99x", "9.9.0");
    addModule(root, "es80x", "8.0.0");
    addModule(root, "es81x", "8.1.0");
    addModule(root, "es82x", "8.2.0");
    addModule(root, "es60x", "6.0.0");
    addModule(root, "es61x", "6.1.0");
    return root;
  }

  private static void addModule(Project root, String name, String supportedEsVersions) {
    Project module = ProjectBuilder.builder().withName(name).withParent(root).build();
    module.getExtensions().getExtraProperties().set("supportedEsVersions", supportedEsVersions);
  }
}
