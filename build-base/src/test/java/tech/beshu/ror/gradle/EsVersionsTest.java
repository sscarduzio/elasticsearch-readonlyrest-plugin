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

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    assertThrows(GradleException.class, () -> EsVersions.of(project("10.0.0-beta1, 10.0.0-alpha1, 10.0.0")));
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

  // ---

  private static Project project(String supportedEsVersions) {
    Project p = ProjectBuilder.builder().build();
    p.getExtensions().getExtraProperties().set("supportedEsVersions", supportedEsVersions);
    return p;
  }
}
