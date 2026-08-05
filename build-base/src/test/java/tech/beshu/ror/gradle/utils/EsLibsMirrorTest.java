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
import org.junit.jupiter.api.io.TempDir;
import tech.beshu.ror.gradle.utils.EsLibsMirror.MirrorPlan;
import tech.beshu.ror.gradle.utils.EsLibsMirror.MirroredJar;
import tech.beshu.ror.gradle.utils.EsLibsMirror.MirroredPom;
import tech.beshu.ror.gradle.utils.MavenPoms.Coordinate;
import tech.beshu.ror.gradle.utils.MavenPoms.Dependency;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class EsLibsMirrorTest {

  private static final Coordinate ELASTICSEARCH =
      new Coordinate("org.elasticsearch", "elasticsearch");
  private static final Coordinate REST_CLIENT =
      new Coordinate("org.elasticsearch.client", "elasticsearch-rest-client");

  @TempDir Path tempDir;

  // --- which published version the POMs are shaped after ---

  @Test
  void takesTheNewestPublishedVersionBelowTheTarget() {
    List<String> published = List.of("9.3.8", "9.4.0", "9.4.4");

    assertEquals("9.4.4", EsLibsMirror.referenceVersion(published, "9.5.0"));
  }

  @Test
  void prefersTheSameMajorEvenWhenAnOlderMajorPublishedLater() {
    List<String> published = List.of("8.19.19", "9.4.4");

    assertEquals("8.19.19", EsLibsMirror.referenceVersion(published, "8.19.20"));
  }

  @Test
  void usesTheTargetItselfOnceElasticHasPublishedIt() {
    assertEquals("9.5.0", EsLibsMirror.referenceVersion(List.of("9.4.4", "9.5.0"), "9.5.0"));
  }

  @Test
  void ignoresVersionsAboveTheTarget() {
    assertEquals("9.4.4", EsLibsMirror.referenceVersion(List.of("9.4.4", "9.6.0"), "9.5.0"));
  }

  @Test
  void nothingPublishedBelowTheTargetThrows() {
    assertThrows(
        GradleException.class, () -> EsLibsMirror.referenceVersion(List.of("9.6.0"), "9.5.0"));
  }

  // --- rewriting the reference dependencies for the target ---

  @Test
  void esVersionedDependenciesMoveToTheTargetVersion() {
    EsDistribution distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/elasticsearch-core-9.5.0.jar");
    Map<Coordinate, List<Dependency>> reference =
        Map.of(
            ELASTICSEARCH,
            List.of(dependency("org.elasticsearch", "elasticsearch-core", "9.4.4", "compile")));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    assertEquals("9.5.0", onlyPom(plan).dependencies().get(0).version());
  }

  @Test
  void thirdPartyDependenciesTakeTheBundledVersion() {
    EsDistribution distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/lucene-core-10.5.0.jar");
    Map<Coordinate, List<Dependency>> reference =
        Map.of(
            ELASTICSEARCH,
            List.of(dependency("org.apache.lucene", "lucene-core", "10.4.0", "compile")));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    assertEquals("10.5.0", onlyPom(plan).dependencies().get(0).version());
  }

  @Test
  void dependenciesTheDistributionDoesNotBundleKeepElasticsVersion() {
    // ES ships log4j-api but not log4j-core, so Elastic's own version is all we have to go on.
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");
    Map<Coordinate, List<Dependency>> reference =
        Map.of(
            ELASTICSEARCH,
            List.of(dependency("org.apache.logging.log4j", "log4j-core", "2.26.0", "compile")));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    assertEquals("2.26.0", onlyPom(plan).dependencies().get(0).version());
  }

  @Test
  void esVersionedDependencyMissingFromTheDistributionThrows() {
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");
    Map<Coordinate, List<Dependency>> reference =
        Map.of(
            ELASTICSEARCH,
            List.of(dependency("org.elasticsearch", "elasticsearch-dropped", "9.4.4", "compile")));

    GradleException failure =
        assertThrows(
            GradleException.class,
            () -> EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference));

    assertTrue(failure.getMessage().contains("elasticsearch-dropped"));
  }

  @Test
  void mirroredCoordinateMissingFromTheDistributionThrows() {
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");

    assertThrows(
        GradleException.class,
        () -> EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", Map.of(REST_CLIENT, List.of())));
  }

  @Test
  void scopesSurviveTheRewrite() {
    EsDistribution distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/elasticsearch-native-9.5.0.jar");
    Map<Coordinate, List<Dependency>> reference =
        Map.of(
            ELASTICSEARCH,
            List.of(dependency("org.elasticsearch", "elasticsearch-native", "9.4.4", "runtime")));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    assertEquals("runtime", onlyPom(plan).dependencies().get(0).scope());
  }

  @Test
  void versionComesFromTheCopyShippedAlongsideTheArtifact() {
    EsDistribution distribution =
        distributionWith(
            "modules/reindex/elasticsearch-rest-client-9.5.0.jar",
            "modules/reindex/commons-codec-1.15.jar",
            "modules/ingest-attachment/commons-codec-1.19.0.jar");
    Map<Coordinate, List<Dependency>> reference =
        Map.of(
            REST_CLIENT, List.of(dependency("commons-codec", "commons-codec", "1.15", "compile")));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    assertEquals("1.15", onlyPom(plan).dependencies().get(0).version());
  }

  // --- which jars follow from the POMs ---

  @Test
  void mirrorsTheCoordinateItselfAndItsEsDependenciesOnly() {
    EsDistribution distribution =
        distributionWith(
            "lib/elasticsearch-9.5.0.jar",
            "lib/elasticsearch-core-9.5.0.jar",
            "lib/elasticsearch-plugin-api-9.5.0.jar",
            "lib/lucene-core-10.5.0.jar");
    Map<Coordinate, List<Dependency>> reference =
        Map.of(
            ELASTICSEARCH,
            List.of(
                dependency("org.elasticsearch", "elasticsearch-core", "9.4.4", "compile"),
                dependency(
                    "org.elasticsearch.plugin", "elasticsearch-plugin-api", "9.4.4", "compile"),
                dependency("org.apache.lucene", "lucene-core", "10.4.0", "compile")));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    assertEquals(
        List.of("elasticsearch", "elasticsearch-core", "elasticsearch-plugin-api"),
        plan.jars().stream().map(jar -> jar.coordinate().artifactId()).sorted().toList());
  }

  @Test
  void mirroredJarKeepsTheGroupElasticDeclared() {
    EsDistribution distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/elasticsearch-plugin-api-9.5.0.jar");
    Map<Coordinate, List<Dependency>> reference =
        Map.of(
            ELASTICSEARCH,
            List.of(
                dependency(
                    "org.elasticsearch.plugin", "elasticsearch-plugin-api", "9.4.4", "compile")));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    MirroredJar pluginApi =
        plan.jars().stream()
            .filter(jar -> jar.coordinate().artifactId().equals("elasticsearch-plugin-api"))
            .findFirst()
            .orElseThrow();
    assertEquals("org.elasticsearch.plugin", pluginApi.coordinate().groupId());
  }

  @Test
  void aJarSharedBySeveralPomsIsMirroredOnce() {
    EsDistribution distribution =
        distributionWith(
            "lib/elasticsearch-9.5.0.jar",
            "lib/elasticsearch-core-9.5.0.jar",
            "modules/reindex/elasticsearch-rest-client-9.5.0.jar");
    Dependency core = dependency("org.elasticsearch", "elasticsearch-core", "9.4.4", "compile");
    Map<Coordinate, List<Dependency>> reference =
        Map.of(ELASTICSEARCH, List.of(core), REST_CLIENT, List.of(core));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    assertEquals(
        1,
        plan.jars().stream()
            .filter(jar -> jar.coordinate().artifactId().equals("elasticsearch-core"))
            .count());
  }

  private static MirroredPom onlyPom(MirrorPlan plan) {
    assertEquals(1, plan.poms().size());
    return plan.poms().get(0);
  }

  private static Dependency dependency(
      String groupId, String artifactId, String version, String scope) {
    return new Dependency(groupId, artifactId, version, scope);
  }

  private EsDistribution distributionWith(String... relativeJarPaths) {
    try {
      Path distribution = tempDir.resolve("es");
      for (String relativePath : relativeJarPaths) {
        Path jar = distribution.resolve(relativePath);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "");
      }
      Files.createDirectories(distribution.resolve("lib"));
      return EsDistribution.scan(distribution);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
