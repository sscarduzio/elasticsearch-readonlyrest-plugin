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
import java.util.Optional;

class EsLibsMirrorTest {

  private static final MavenCoordinate REST_CLIENT =
      new MavenCoordinate("org.elasticsearch.client", "elasticsearch-rest-client");

  /** Places nothing but the jars the release itself ships, as the real chain's last step does. */
  private static final ArtifactGroups ELASTICS = EsGroups.shippedWith("9.5.0");

  @TempDir Path tempDir;

  // --- what a POM declares ---

  @Test
  void theServersDependenciesAreTheJarsShippedBesideItInLib() throws IOException {
    EsDistribution distribution =
        distributionWith(
            "lib/elasticsearch-9.5.0.jar",
            "lib/elasticsearch-core-9.5.0.jar",
            "lib/lucene-core-10.5.0.jar");

    MirrorPlan plan = planFor(distribution, placing("lucene-core:10.5.0", "org.apache.lucene"));

    assertEquals(
        List.of(
            "org.elasticsearch:elasticsearch-core:9.5.0", "org.apache.lucene:lucene-core:10.5.0"),
        declared(onlyPom(plan)));
  }

  @Test
  void aJarThisReleaseAddedIsDeclaredWithoutAnythingHavingToNameIt() throws IOException {
    // ES 9.5.0 added elasticsearch-ip-location-api, which the 9.4.4 POM knows nothing about.
    EsDistribution distribution =
        distributionWith(
            "lib/elasticsearch-9.5.0.jar", "lib/elasticsearch-ip-location-api-9.5.0.jar");

    MirrorPlan plan = planFor(distribution, ELASTICS);

    assertEquals(
        List.of("org.elasticsearch:elasticsearch-ip-location-api:9.5.0"), declared(onlyPom(plan)));
    assertTrue(mirrored(plan).contains("elasticsearch-ip-location-api"));
  }

  @Test
  void aModulesDependenciesAreTheJarsInItsOwnDirectory() throws IOException {
    EsDistribution distribution =
        distributionWith(
            "modules/transport-netty4/transport-netty4-9.5.0.jar",
            "modules/transport-netty4/netty-buffer-4.1.135.Final.jar",
            "lib/elasticsearch-9.5.0.jar");

    MirrorPlan plan =
        EsLibsMirror.planFor(
            distribution,
            List.of("transport-netty4"),
            placing("netty-buffer:4.1.135.Final", "io.netty"));

    assertEquals(List.of("io.netty:netty-buffer:4.1.135.Final"), declared(onlyPom(plan)));
  }

  @Test
  void theModuleShippingAnArtifactIsNotOneOfItsDependencies() throws IOException {
    // The rest client rides along in modules/reindex, whose own jar is nothing to do with it.
    EsDistribution distribution =
        distributionWith(
            "modules/reindex/elasticsearch-rest-client-9.5.0.jar",
            "modules/reindex/reindex-9.5.0.jar",
            "modules/reindex/httpcore-4.4.16.jar");

    MirrorPlan plan =
        EsLibsMirror.planFor(
            distribution,
            List.of("elasticsearch-rest-client"),
            placing("httpcore:4.4.16", "org.apache.httpcomponents"));

    assertEquals(List.of("org.apache.httpcomponents:httpcore:4.4.16"), declared(onlyPom(plan)));
  }

  @Test
  void thePomCarriesTheVersionOfTheJarItIsPublishedBeside() throws IOException {
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");

    MirrorPlan plan = planFor(distribution, ELASTICS);

    assertEquals("9.5.0", onlyPom(plan).version());
  }

  @Test
  void theCoordinateMissingFromTheDistributionThrows() throws IOException {
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");

    GradleException failure =
        assertThrows(
            GradleException.class,
            () ->
                EsLibsMirror.planFor(distribution, List.of("elasticsearch-rest-client"), ELASTICS));

    assertTrue(failure.getMessage().contains("elasticsearch-rest-client"));
  }

  // --- the group a jar is published under ---

  @Test
  void anArtifactElasticShipsWithoutPublishingTakesTheirGroup() throws IOException {
    // elasticsearch-log4j is log4j-core repackaged, and is on no repository to be looked up in.
    EsDistribution distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/elasticsearch-log4j-9.5.0.jar");

    MirrorPlan plan = planFor(distribution, ELASTICS);

    assertEquals(List.of("org.elasticsearch:elasticsearch-log4j:9.5.0"), declared(onlyPom(plan)));
  }

  @Test
  void anArtifactOfElasticsIsPlacedByWhatPublishesItRatherThanByItsName() throws IOException {
    // Named like the rest of them, published under another group.
    EsDistribution distribution =
        distributionWith(
            "lib/elasticsearch-9.5.0.jar",
            "lib/elasticsearch-plugin-api-9.5.0.jar",
            "lib/elasticsearch-core-9.5.0.jar");

    MirrorPlan plan =
        planFor(
            distribution, placing("elasticsearch-plugin-api:9.5.0", "org.elasticsearch.plugin"));

    assertEquals(
        List.of(
            "org.elasticsearch:elasticsearch-core:9.5.0",
            "org.elasticsearch.plugin:elasticsearch-plugin-api:9.5.0"),
        declared(onlyPom(plan)));
  }

  @Test
  void aThirdPartyGroupIsLookedUpAtTheVersionTheDistributionShips() throws IOException {
    EsDistribution distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/commons-codec-1.15.jar");

    MirrorPlan plan = planFor(distribution, placing("commons-codec:1.15", "commons-codec"));

    assertEquals(List.of("commons-codec:commons-codec:1.15"), declared(onlyPom(plan)));
  }

  @Test
  void aJarNoSourceCanPlaceThrows() throws IOException {
    EsDistribution distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/mystery-1.0.jar");

    GradleException failure =
        assertThrows(GradleException.class, () -> planFor(distribution, ELASTICS));

    assertTrue(failure.getMessage().contains("mystery-1.0.jar"));
  }

  // --- jars to publish ---

  @Test
  void onlyElasticsOwnArtifactsAreMirrored() throws IOException {
    // Third-party artifacts are published under their own versions, which Central has already.
    EsDistribution distribution =
        distributionWith(
            "lib/elasticsearch-9.5.0.jar",
            "lib/elasticsearch-core-9.5.0.jar",
            "lib/lucene-core-10.5.0.jar");

    MirrorPlan plan = planFor(distribution, placing("lucene-core:10.5.0", "org.apache.lucene"));

    assertEquals(List.of("elasticsearch", "elasticsearch-core"), mirrored(plan));
  }

  @Test
  void aJarSharedByTwoPomsIsMirroredOnce() throws IOException {
    EsDistribution distribution =
        distributionWith(
            "lib/elasticsearch-9.5.0.jar",
            "lib/elasticsearch-ssl-config-9.5.0.jar",
            "modules/transport-netty4/transport-netty4-9.5.0.jar",
            "modules/transport-netty4/elasticsearch-ssl-config-9.5.0.jar");

    MirrorPlan plan =
        EsLibsMirror.planFor(distribution, List.of("elasticsearch", "transport-netty4"), ELASTICS);

    assertEquals(
        1, mirrored(plan).stream().filter(id -> id.equals("elasticsearch-ssl-config")).count());
  }

  @Test
  void mirroredFilesAreNamedAsMavenExpects() throws IOException {
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");

    MirrorPlan plan = planFor(distribution, ELASTICS);

    assertEquals("elasticsearch-9.5.0.jar", plan.jars().get(0).fileName());
    assertEquals("elasticsearch-9.5.0.pom", onlyPom(plan).fileName());
  }

  // --- pomFor() ---

  @Test
  void aMirroredCoordinateUploadsItsPomAlongsideItsJar() throws IOException {
    EsDistribution distribution = distributionWith("lib/elasticsearch-9.5.0.jar");

    MirrorPlan plan = planFor(distribution, ELASTICS);

    assertEquals(
        "elasticsearch-9.5.0.pom", plan.pomFor(plan.jars().get(0)).orElseThrow().fileName());
  }

  @Test
  void aJarMirroredOnlyAsADependencyHasNoPom() throws IOException {
    EsDistribution distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/elasticsearch-core-9.5.0.jar");

    MirrorPlan plan = planFor(distribution, ELASTICS);

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
        new MirrorPlan(List.of(new MirroredPom(REST_CLIENT, "9.6.0", List.of())), List.of(jar));

    GradleException failure = assertThrows(GradleException.class, () -> plan.pomFor(jar));

    assertTrue(failure.getMessage().contains("elasticsearch-rest-client-9.6.0.pom"));
  }

  private static MirrorPlan planFor(EsDistribution distribution, ArtifactGroups groups) {
    return EsLibsMirror.planFor(distribution, List.of("elasticsearch"), groups);
  }

  /** Stands in for what places an artifact: a group per artifact and version, and nothing else. */
  /** One artifact placed explicitly, on top of what the release ships. */
  private static ArtifactGroups placing(String artifactAndVersion, String groupId) {
    Map<String, String> known = Map.of(artifactAndVersion, groupId);
    ArtifactGroups explicit =
        (artifactId, version) -> Optional.ofNullable(known.get(artifactId + ":" + version));
    return ArtifactGroups.firstOf(explicit, ELASTICS);
  }

  private static List<String> declared(MirroredPom pom) {
    return pom.dependencies().stream().map(EsLibsMirrorTest::coordinatesOf).toList();
  }

  private static String coordinatesOf(Dependency dependency) {
    return dependency.groupId() + ":" + dependency.artifactId() + ":" + dependency.version();
  }

  private static List<String> mirrored(MirrorPlan plan) {
    return plan.jars().stream().map(jar -> jar.coordinate().artifactId()).sorted().toList();
  }

  private static MirroredPom onlyPom(MirrorPlan plan) {
    assertEquals(1, plan.poms().size());
    return plan.poms().get(0);
  }

  private EsDistribution distributionWith(String... relativeJarPaths) throws IOException {
    Path distribution = tempDir.resolve("es");
    for (String relativePath : relativeJarPaths) {
      Path jar = distribution.resolve(relativePath);
      Files.createDirectories(jar.getParent());
      Files.writeString(jar, "");
    }
    Files.createDirectories(distribution.resolve("lib"));
    return EsDistribution.scan(distribution);
  }
}
