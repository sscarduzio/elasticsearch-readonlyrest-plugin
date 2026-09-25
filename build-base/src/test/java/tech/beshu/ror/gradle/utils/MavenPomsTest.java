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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import tech.beshu.ror.gradle.utils.MavenPoms.Dependency;

import java.util.List;

class MavenPomsTest {

  // --- parseDependencies() ---

  @Test
  void readsDependenciesInDeclarationOrder() {
    List<Dependency> dependencies = MavenPoms.parseDependencies(publishedPom());

    assertEquals(3, dependencies.size());
    assertEquals("elasticsearch-core", dependencies.get(0).artifactId());
    assertEquals("lucene-core", dependencies.get(1).artifactId());
    assertEquals("elasticsearch-native", dependencies.get(2).artifactId());
  }

  @Test
  void keepsGroupVersionAndScope() {
    Dependency lucene = MavenPoms.parseDependencies(publishedPom()).get(1);

    assertEquals("org.apache.lucene", lucene.groupId());
    assertEquals("10.4.0", lucene.version());
    assertEquals("compile", lucene.scope());
  }

  @Test
  void keepsRuntimeScope() {
    assertEquals("runtime", MavenPoms.parseDependencies(publishedPom()).get(2).scope());
  }

  @Test
  void defaultsMissingScopeToCompile() {
    String pom =
        """
        <project><dependencies><dependency>
          <groupId>g</groupId><artifactId>a</artifactId><version>1</version>
        </dependency></dependencies></project>
        """;

    assertEquals("compile", MavenPoms.parseDependencies(pom).get(0).scope());
  }

  @Test
  void ignoresDependencyManagement() {
    String pom =
        """
        <project>
          <dependencyManagement><dependencies><dependency>
            <groupId>managed</groupId><artifactId>managed</artifactId><version>9</version>
          </dependency></dependencies></dependencyManagement>
          <dependencies><dependency>
            <groupId>real</groupId><artifactId>real</artifactId><version>1</version>
          </dependency></dependencies>
        </project>
        """;

    List<Dependency> dependencies = MavenPoms.parseDependencies(pom);

    assertEquals(1, dependencies.size());
    assertEquals("real", dependencies.get(0).groupId());
  }

  @Test
  void pomWithoutDependenciesYieldsNothing() {
    assertTrue(
        MavenPoms.parseDependencies("<project><modelVersion>4.0.0</modelVersion></project>")
            .isEmpty());
  }

  @Test
  void malformedPomThrows() {
    assertThrows(GradleException.class, () -> MavenPoms.parseDependencies("<project>"));
  }

  // --- render() ---

  @Test
  void rendersAPomThatCanBeReadBack() {
    List<Dependency> dependencies =
        List.of(
            new Dependency("org.elasticsearch", "elasticsearch-core", "9.5.0", "compile"),
            new Dependency("org.elasticsearch", "elasticsearch-native", "9.5.0", "runtime"));

    String rendered =
        MavenPoms.render(
            new MavenCoordinate("org.elasticsearch", "elasticsearch"), "9.5.0", dependencies);

    assertEquals(dependencies, MavenPoms.parseDependencies(rendered));
  }

  @Test
  void rendersOwnCoordinateAndVersion() {
    String rendered =
        MavenPoms.render(
            new MavenCoordinate("org.elasticsearch.plugin", "transport-netty4"),
            "9.5.0",
            List.of());

    assertTrue(rendered.contains("<groupId>org.elasticsearch.plugin</groupId>"));
    assertTrue(rendered.contains("<artifactId>transport-netty4</artifactId>"));
    assertTrue(rendered.contains("<version>9.5.0</version>"));
    assertTrue(rendered.contains("<packaging>jar</packaging>"));
  }

  // --- MavenCoordinate ---

  @Test
  void repositoryPathFollowsMavenLayout() {
    MavenCoordinate restClient =
        new MavenCoordinate("org.elasticsearch.client", "elasticsearch-rest-client");

    assertEquals("org/elasticsearch/client/elasticsearch-rest-client", restClient.repositoryPath());
    assertEquals(
        "org/elasticsearch/client/elasticsearch-rest-client/9.5.0",
        restClient.repositoryPath("9.5.0"));
  }

  @Test
  void pomIsNamedForTheArtifactAndVersion() {
    assertEquals(
        "elasticsearch-rest-client-9.5.0.pom",
        new MavenCoordinate("org.elasticsearch.client", "elasticsearch-rest-client")
            .pomFileName("9.5.0"));
  }

  private static String publishedPom() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>org.elasticsearch</groupId>
          <artifactId>elasticsearch</artifactId>
          <version>9.4.4</version>
          <dependencies>
            <dependency>
              <groupId>org.elasticsearch</groupId>
              <artifactId>elasticsearch-core</artifactId>
              <version>9.4.4</version>
              <scope>compile</scope>
            </dependency>
            <dependency>
              <groupId>org.apache.lucene</groupId>
              <artifactId>lucene-core</artifactId>
              <version>10.4.0</version>
              <scope>compile</scope>
            </dependency>
            <dependency>
              <groupId>org.elasticsearch</groupId>
              <artifactId>elasticsearch-native</artifactId>
              <version>9.4.4</version>
              <scope>runtime</scope>
            </dependency>
          </dependencies>
        </project>
        """;
  }
}
