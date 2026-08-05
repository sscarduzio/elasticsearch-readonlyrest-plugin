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
import tech.beshu.ror.gradle.utils.MavenPoms.Dependency;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class EsLibsMirrorTest {

  private static final MavenCoordinate ELASTICSEARCH =
      new MavenCoordinate("org.elasticsearch", "elasticsearch");
  private static final MavenCoordinate REST_CLIENT =
      new MavenCoordinate("org.elasticsearch.client", "elasticsearch-rest-client");

  @TempDir Path tempDir;

  // --- referenceVersion() ---

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

  // --- dependency versions in the generated POMs ---

  @Test
  void esVersionedDependenciesMoveToTheTargetVersion() {
    EsDistribution distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/elasticsearch-core-9.5.0.jar");
    Map<MavenCoordinate, List<Dependency>> reference =
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
    Map<MavenCoordinate, List<Dependency>> reference =
        Map.of(
            ELASTICSEARCH,
            List.of(dependency("org.apache.lucene", "lucene-core", "10.4.0", "compile")));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    assertEquals("10.5.0", onlyPom(plan).dependencies().get(0).version());
  }

  @Test
  void dependenciesTheDistributionDoesNotBundleKeepElasticsVersion() {
    // ES ships log4j-api but not log4j-core.
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");
    Map<MavenCoordinate, List<Dependency>> reference =
        Map.of(
            ELASTICSEARCH,
            List.of(dependency("org.apache.logging.log4j", "log4j-core", "2.26.0", "compile")));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    assertEquals("2.26.0", onlyPom(plan).dependencies().get(0).version());
  }

  @Test
  void esVersionedDependencyMissingFromTheDistributionThrows() {
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");
    Map<MavenCoordinate, List<Dependency>> reference =
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
    Map<MavenCoordinate, List<Dependency>> reference =
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
    Map<MavenCoordinate, List<Dependency>> reference =
        Map.of(
            REST_CLIENT, List.of(dependency("commons-codec", "commons-codec", "1.15", "compile")));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    assertEquals("1.15", onlyPom(plan).dependencies().get(0).version());
  }

  // --- jars to publish ---

  @Test
  void mirrorsTheCoordinateItselfAndItsEsDependenciesOnly() {
    EsDistribution distribution =
        distributionWith(
            "lib/elasticsearch-9.5.0.jar",
            "lib/elasticsearch-core-9.5.0.jar",
            "lib/elasticsearch-plugin-api-9.5.0.jar",
            "lib/lucene-core-10.5.0.jar");
    Map<MavenCoordinate, List<Dependency>> reference =
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
    Map<MavenCoordinate, List<Dependency>> reference =
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
    Map<MavenCoordinate, List<Dependency>> reference =
        Map.of(ELASTICSEARCH, List.of(core), REST_CLIENT, List.of(core));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    assertEquals(
        1,
        plan.jars().stream()
            .filter(jar -> jar.coordinate().artifactId().equals("elasticsearch-core"))
            .count());
  }

  @Test
  void mirroredFilesAreNamedAsMavenExpects() {
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");

    MirrorPlan plan =
        EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", Map.of(ELASTICSEARCH, List.of()));

    assertEquals("elasticsearch-9.5.0.jar", plan.jars().get(0).fileName());
    assertEquals("elasticsearch-9.5.0.pom", onlyPom(plan).fileName());
  }

  // --- pomFor() ---

  @Test
  void aMirroredCoordinateUploadsItsPomAlongsideItsJar() {
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");

    MirrorPlan plan =
        EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", Map.of(ELASTICSEARCH, List.of()));

    assertEquals(
        "elasticsearch-9.5.0.pom", plan.pomFor(plan.jars().get(0)).orElseThrow().fileName());
  }

  @Test
  void aJarMirroredOnlyAsADependencyHasNoPom() {
    EsDistribution distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/elasticsearch-core-9.5.0.jar");
    Map<MavenCoordinate, List<Dependency>> reference =
        Map.of(
            ELASTICSEARCH,
            List.of(dependency("org.elasticsearch", "elasticsearch-core", "9.4.4", "compile")));

    MirrorPlan plan = EsLibsMirror.plan(distribution, "9.5.0", "9.4.4", reference);

    MirroredJar core =
        plan.jars().stream()
            .filter(jar -> jar.coordinate().artifactId().equals("elasticsearch-core"))
            .findFirst()
            .orElseThrow();
    assertTrue(plan.pomFor(core).isEmpty());
  }

  @Test
  void aPomNamingAnotherVersionThanTheBundledJarThrows() {
    // The upload names the repository directory after the jar, so the POM would land where Maven
    // never looks.
    MirroredJar jar =
        new MirroredJar(REST_CLIENT, "9.5.0", Path.of("elasticsearch-rest-client-9.5.0.jar"));
    MirrorPlan plan =
        new MirrorPlan(
            "9.4.4", List.of(new MirroredPom(REST_CLIENT, "9.6.0", List.of())), List.of(jar));

    GradleException failure = assertThrows(GradleException.class, () -> plan.pomFor(jar));

    assertTrue(failure.getMessage().contains("elasticsearch-rest-client-9.6.0.pom"));
  }

  // --- planFor() ---

  @Test
  void takesTheDependenciesFromThePomOfTheReferenceVersion() throws IOException {
    EsDistribution distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/elasticsearch-core-9.5.0.jar");
    MavenRepository central =
        repositoryPublishing(
            List.of("9.3.8", "9.4.4"),
            "9.4.4",
            List.of(dependency("org.elasticsearch", "elasticsearch-core", "9.4.4", "compile")));

    MirrorPlan plan = EsLibsMirror.planFor(distribution, "9.5.0", List.of(ELASTICSEARCH), central);

    assertEquals("9.4.4", plan.referenceVersion());
    assertEquals("9.5.0", onlyPom(plan).dependencies().get(0).version());
    assertEquals(
        List.of("elasticsearch", "elasticsearch-core"),
        plan.jars().stream().map(jar -> jar.coordinate().artifactId()).sorted().toList());
  }

  @Test
  void referencePomTheRepositoryDoesNotServeThrows() throws IOException {
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");
    MavenRepository central = repositoryPublishing(List.of("9.4.4"), "9.3.8", List.of());

    assertThrows(
        GradleException.class,
        () -> EsLibsMirror.planFor(distribution, "9.5.0", List.of(ELASTICSEARCH), central));
  }

  private MavenRepository repositoryPublishing(
      List<String> publishedVersions, String pomVersion, List<Dependency> dependencies)
      throws IOException {
    Path repository = tempDir.resolve("central");
    StringBuilder metadata = new StringBuilder("<metadata><versioning><versions>");
    publishedVersions.forEach(
        version -> metadata.append("<version>").append(version).append("</version>"));
    metadata.append("</versions></versioning></metadata>");

    write(
        repository.resolve(ELASTICSEARCH.repositoryPath() + "/maven-metadata.xml"),
        metadata.toString());
    write(
        repository.resolve(
            ELASTICSEARCH.repositoryPath(pomVersion) + "/" + ELASTICSEARCH.pomFileName(pomVersion)),
        MavenPoms.render(ELASTICSEARCH, pomVersion, dependencies));
    return MavenRepository.at(repository.toUri().toString());
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
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
