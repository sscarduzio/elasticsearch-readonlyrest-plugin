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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import tech.beshu.ror.gradle.utils.TestMatrixPolicy.Selection;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

class TestMatrixPolicyTest {

  // --- select, inside one ES major ---

  @Test
  void allKeepsEveryModuleAndTheirOrder() {
    assertEquals(modules(3), TestMatrixPolicy.select(modules(3), Selection.ALL));
  }

  @Test
  void newestTakesTheFirstModule() {
    assertEquals(List.of("es3x"), TestMatrixPolicy.select(modules(3), Selection.NEWEST));
  }

  @Test
  void oldestAndNewestTakesBothEnds() {
    assertEquals(
        List.of("es9x", "es1x"), TestMatrixPolicy.select(modules(9), Selection.OLDEST_AND_NEWEST));
  }

  @Test
  void aReadyPrTakesBothEndsOfASmallMajor() {
    assertEquals(List.of("es9x", "es1x"), TestMatrixPolicy.select(modules(9), Selection.READY_PR));
  }

  @Test
  void aReadyPrAlsoTakesAMiddleModuleOfALargeMajor() {
    // Ten modules reach the threshold. es5x sits in the middle of es10x..es1x.
    assertEquals(
        List.of("es10x", "es5x", "es1x"), TestMatrixPolicy.select(modules(10), Selection.READY_PR));
  }

  @Test
  void aMajorWithOneModuleGivesItOnce() {
    for (Selection selection : Selection.values()) {
      assertEquals(List.of("es1x"), TestMatrixPolicy.select(modules(1), selection));
    }
  }

  @Test
  void aMajorWithNoModuleIsAnError() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TestMatrixPolicy.select(Collections.emptyList(), Selection.ALL));
  }

  // --- modulesFor, across ES majors ---

  @Test
  void everyMajorIsSelectedFromOnItsOwn() {
    Project root =
        rootWithModules(
            "es94x:9.4.0", "es90x:9.0.0", "es818x:8.18.0", "es80x:8.0.0", "es67x:6.7.0");
    assertEquals(
        List.of("es94x", "es90x", "es818x", "es80x", "es67x"),
        TestMatrixPolicy.modulesFor(root, Selection.OLDEST_AND_NEWEST, Set.of()));
  }

  @Test
  void aSkippedMajorContributesNothing() {
    Project root = rootWithModules("es94x:9.4.0", "es818x:8.18.0", "es67x:6.7.0");
    // Windows and e2e do not run on ES 6.
    assertEquals(
        List.of("es94x", "es818x"), TestMatrixPolicy.modulesFor(root, Selection.NEWEST, Set.of(6)));
  }

  @Test
  void aNewMajorJoinsTheMatrixWithoutAnEdit() {
    Project root = rootWithModules("es100x:10.0.0", "es94x:9.4.0");
    assertEquals(
        List.of("es100x", "es94x"), TestMatrixPolicy.modulesFor(root, Selection.ALL, Set.of()));
  }

  /** es{count}x .. es1x: newest first, the order every selection takes. */
  private static List<String> modules(int count) {
    return IntStream.iterate(count, module -> module - 1)
        .limit(count)
        .mapToObj(module -> "es" + module + "x")
        .collect(Collectors.toList());
  }

  private static Project rootWithModules(String... specs) {
    Project root = ProjectBuilder.builder().build();
    for (String spec : specs) {
      String[] parts = spec.split(":", 2);
      Project child = ProjectBuilder.builder().withName(parts[0]).withParent(root).build();
      child.getExtensions().getExtraProperties().set("supportedEsVersions", parts[1]);
    }
    return root;
  }
}
