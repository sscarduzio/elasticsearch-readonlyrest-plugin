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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

class EsModuleFinderTest {

  // --- findEsModuleFor ---

  @Test
  void findsModuleWhenVersionIsListed() {
    Project root = rootWithModules("es818x:8.18.0, 8.19.0", "es92x:9.2.0");
    Optional<Project> found = EsModuleFinder.findEsModuleFor(root, "8.18.0");
    assertTrue(found.isPresent());
    assertEquals("es818x", found.get().getName());
  }

  @Test
  void findsModuleForNewestVersionInList() {
    Project root = rootWithModules("es818x:8.18.0, 8.19.0", "es92x:9.2.0");
    Optional<Project> found = EsModuleFinder.findEsModuleFor(root, "8.19.0");
    assertTrue(found.isPresent());
    assertEquals("es818x", found.get().getName());
  }

  @Test
  void returnsEmptyWhenVersionNotInAnyModule() {
    Project root = rootWithModules("es818x:8.18.0, 8.19.0");
    assertTrue(EsModuleFinder.findEsModuleFor(root, "8.17.0").isEmpty());
  }

  @Test
  void nonEsChildProjectsAreIgnored() {
    Project root = rootWithModules("es818x:8.18.0");
    ProjectBuilder.builder().withName("core").withParent(root).build();
    Optional<Project> found = EsModuleFinder.findEsModuleFor(root, "8.18.0");
    assertTrue(found.isPresent());
    assertEquals("es818x", found.get().getName());
  }

  @Test
  void returnsEmptyWhenNoEsModulesExist() {
    Project root = ProjectBuilder.builder().build();
    ProjectBuilder.builder().withName("core").withParent(root).build();
    assertTrue(EsModuleFinder.findEsModuleFor(root, "8.18.0").isEmpty());
  }

  // --- findTheNewestSupportedEsVersion ---

  @Test
  void findsNewestVersionAcrossAllModules() {
    Project root = rootWithModules("es818x:8.18.0, 8.19.0", "es92x:9.2.0");
    assertEquals(Optional.of("9.2.0"), EsModuleFinder.findTheNewestSupportedEsVersion(root));
  }

  @Test
  void findsNewestVersionReturnsEmptyWhenNoEsModules() {
    assertTrue(
        EsModuleFinder.findTheNewestSupportedEsVersion(ProjectBuilder.builder().build()).isEmpty());
  }

  // --- newestEsVersionFor / supportedEsVersionsFor ---

  @Test
  void newestEsVersionForReturnsLastInList() {
    Project root = rootWithModules("es818x:8.18.0, 8.18.1, 8.19.0");
    assertEquals(
        "8.19.0", EsModuleFinder.newestEsVersionFor(root.getChildProjects().get("es818x")));
  }

  // --- sortedEsModules / newestEsVersionComparator ---

  @Test
  void sortedEsModulesAscendingByNewest() {
    Project root = rootWithModules("es92x:9.2.0", "es818x:8.18.0, 8.19.0");
    List<String> names =
        EsModuleFinder.sortedEsModules(root, EsModuleFinder.newestEsVersionComparator()).stream()
            .map(Project::getName)
            .toList();
    assertEquals(List.of("es818x", "es92x"), names);
  }

  @Test
  void sortedEsModulesDescendingByNewest() {
    Project root = rootWithModules("es818x:8.18.0, 8.19.0", "es92x:9.2.0");
    List<String> names =
        EsModuleFinder.sortedEsModules(root, EsModuleFinder.newestEsVersionComparator().reversed())
            .stream()
            .map(Project::getName)
            .toList();
    assertEquals(List.of("es92x", "es818x"), names);
  }

  // ---

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
