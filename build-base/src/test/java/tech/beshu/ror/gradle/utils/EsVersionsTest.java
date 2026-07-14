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

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

class EsVersionsTest {

  // --- of() / field correctness ---

  @Test
  void oldestFirstOrderPassesAndSetsFields() {
    EsVersions v = EsVersions.of(project("8.18.0, 8.18.1, 8.19.0"));
    assertEquals("8.18.0", v.oldest);
    assertEquals("8.19.0", v.newest);
    assertEquals(List.of("8.18.0", "8.18.1", "8.19.0"), v.all);
  }

  @Test
  void singleVersionIsValid() {
    EsVersions v = EsVersions.of(project("9.2.0"));
    assertEquals("9.2.0", v.oldest);
    assertEquals("9.2.0", v.newest);
  }

  @Test
  void misorderedVersionsThrow() {
    assertThrows(GradleException.class, () -> EsVersions.of(project("8.19.0, 8.18.0")));
  }

  @Test
  void emptyVersionsThrow() {
    assertThrows(GradleException.class, () -> EsVersions.of(project("  ,  ")));
  }

  // --- pre-release ordering ---

  @Test
  void preReleaseBeforeReleaseIsValid() {
    EsVersions v = EsVersions.of(project("10.0.0-alpha1, 10.0.0"));
    assertEquals("10.0.0-alpha1", v.oldest);
    assertEquals("10.0.0", v.newest);
  }

  @Test
  void releaseBeforePreReleaseThrows() {
    assertThrows(GradleException.class, () -> EsVersions.of(project("10.0.0, 10.0.0-alpha1")));
  }

  @Test
  void qualifierNumbersComparedNumerically() {
    // alpha10 > alpha2 numerically — would fail with lexicographic comparison
    EsVersions v = EsVersions.of(project("10.0.0-alpha1, 10.0.0-alpha2, 10.0.0-alpha10, 10.0.0"));
    assertEquals("10.0.0-alpha1", v.oldest);
    assertEquals("10.0.0", v.newest);
  }

  @Test
  void qualifierLabelsComparedAlphabetically() {
    // alpha < beta < rc < release
    EsVersions v = EsVersions.of(project("10.0.0-alpha1, 10.0.0-beta1, 10.0.0-rc1, 10.0.0"));
    assertEquals("10.0.0-alpha1", v.oldest);
    assertEquals("10.0.0", v.newest);
  }

  @Test
  void misorderedQualifiersThrow() {
    assertThrows(
        GradleException.class, () -> EsVersions.of(project("10.0.0-beta1, 10.0.0-alpha1, 10.0.0")));
  }

  // --- delivered() / baseline() ---

  @Test
  void deliveredReturnsNewestWhenNoOverride() {
    assertEquals("8.19.0", EsVersions.delivered(project("8.18.0, 8.19.0")));
  }

  @Test
  void deliveredReturnsOverrideWhenEsVersionSet() {
    Project p = project("8.18.0, 8.19.0");
    p.getExtensions().getExtraProperties().set("esVersion", "8.18.5");
    assertEquals("8.18.5", EsVersions.delivered(p));
  }

  @Test
  void baselineReturnsOldestWhenNoOverride() {
    assertEquals("8.18.0", EsVersions.baseline(project("8.18.0, 8.19.0")));
  }

  @Test
  void baselineReturnsOverrideWhenBaselineEsVersionSet() {
    Project p = project("8.18.0, 8.19.0");
    p.getExtensions().getExtraProperties().set("baselineEsVersion", "8.18.0");
    assertEquals("8.18.0", EsVersions.baseline(p));
  }

  // --- baseEsVersions / baseFor / groupNewest ---

  private static final String ES74X_SUPPORTED =
      "7.4.0, 7.4.1, 7.4.2, 7.5.0, 7.5.1, 7.5.2, 7.6.0, 7.6.1, 7.6.2";

  @Test
  void baseVersionsDefaultsToOldestWhenUnset() {
    assertEquals(List.of("7.4.0"), EsVersions.baseVersions(project(ES74X_SUPPORTED)));
  }

  @Test
  void baseVersionsParsedWhenSet() {
    assertEquals(
        List.of("7.4.0", "7.5.0", "7.6.0"),
        EsVersions.baseVersions(projectWithBases(ES74X_SUPPORTED, "7.4.0, 7.5.0, 7.6.0")));
  }

  @Test
  void baseVersionsMustStartAtOldest() {
    assertThrows(
        GradleException.class,
        () -> EsVersions.baseVersions(projectWithBases(ES74X_SUPPORTED, "7.5.0, 7.6.0")));
  }

  @Test
  void baseVersionsMustAllBeSupported() {
    assertThrows(
        GradleException.class,
        () -> EsVersions.baseVersions(projectWithBases(ES74X_SUPPORTED, "7.4.0, 7.5.5")));
  }

  @Test
  void baseVersionsMustBeOrderedOldestFirst() {
    assertThrows(
        GradleException.class,
        () -> EsVersions.baseVersions(projectWithBases(ES74X_SUPPORTED, "7.6.0, 7.4.0, 7.5.0")));
  }

  @Test
  void baseForPicksClosestOldestBase() {
    Project p = projectWithBases(ES74X_SUPPORTED, "7.4.0, 7.5.0, 7.6.0");
    assertEquals("7.4.0", EsVersions.baseFor(p, "7.4.2"));
    assertEquals("7.5.0", EsVersions.baseFor(p, "7.5.0"));
    assertEquals("7.5.0", EsVersions.baseFor(p, "7.5.2"));
    assertEquals("7.6.0", EsVersions.baseFor(p, "7.6.2"));
  }

  @Test
  void baselineUsesTheGroupBaseOfTheDeliveredVersion() {
    Project p = projectWithBases(ES74X_SUPPORTED, "7.4.0, 7.5.0, 7.6.0");
    // delivered defaults to newest (7.6.2) -> its base is 7.6.0
    assertEquals("7.6.0", EsVersions.baseline(p));
    p.getExtensions().getExtraProperties().set("esVersion", "7.5.1");
    assertEquals("7.5.0", EsVersions.baseline(p));
  }

  @Test
  void groupNewestsDefaultsToJustNewest() {
    assertEquals(List.of("7.6.2"), EsVersions.groupNewest(project(ES74X_SUPPORTED)));
  }

  @Test
  void groupNewestsReturnsNewestOfEachGroup() {
    assertEquals(
        List.of("7.4.2", "7.5.2", "7.6.2"),
        EsVersions.groupNewest(projectWithBases(ES74X_SUPPORTED, "7.4.0, 7.5.0, 7.6.0")));
  }

  // ---

  private static Project project(String supportedEsVersions) {
    Project p = ProjectBuilder.builder().build();
    p.getExtensions().getExtraProperties().set("supportedEsVersions", supportedEsVersions);
    return p;
  }

  private static Project projectWithBases(String supportedEsVersions, String baseEsVersions) {
    Project p = project(supportedEsVersions);
    p.getExtensions().getExtraProperties().set("baseEsVersions", baseEsVersions);
    return p;
  }
}
