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
import tech.beshu.ror.gradle.utils.EsDistribution.BundledJar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

class EsDistributionTest {

  @TempDir Path tempDir;

  // --- scan() ---

  @Test
  void indexesJarsFromLibAndEveryModuleDirectory() throws IOException {
    Path distribution =
        distributionWith(
            "lib/elasticsearch-9.5.0.jar", "modules/reindex/elasticsearch-rest-client-9.5.0.jar");

    EsDistribution scanned = EsDistribution.scan(distribution);

    assertEquals(
        "9.5.0", scanned.preferredJarOf("elasticsearch", Set.of()).orElseThrow().version());
    assertEquals(
        "9.5.0",
        scanned.preferredJarOf("elasticsearch-rest-client", Set.of()).orElseThrow().version());
  }

  @Test
  void parsesVersionsWithNonNumericSegments() throws IOException {
    Path distribution = distributionWith("modules/transport-netty4/netty-buffer-4.1.135.Final.jar");

    BundledJar netty =
        EsDistribution.scan(distribution).preferredJarOf("netty-buffer", Set.of()).orElseThrow();

    assertEquals("netty-buffer", netty.artifactId());
    assertEquals("4.1.135.Final", netty.version());
  }

  @Test
  void artifactShippedBySeveralModulesIsFoundInAllOfThem() throws IOException {
    Path distribution =
        distributionWith(
            "modules/reindex/elasticsearch-rest-client-9.5.0.jar",
            "modules/x-pack-monitoring/elasticsearch-rest-client-9.5.0.jar");

    Set<Path> dirs =
        EsDistribution.scan(distribution).directoriesShipping("elasticsearch-rest-client");

    assertEquals(2, dirs.size());
  }

  @Test
  void unknownArtifactIsEmpty() throws IOException {
    Path distribution = distributionWith("lib/elasticsearch-9.5.0.jar");

    assertTrue(EsDistribution.scan(distribution).preferredJarOf("nope", Set.of()).isEmpty());
  }

  @Test
  void scanningSomethingThatIsNotADistributionThrows() {
    assertThrows(GradleException.class, () -> EsDistribution.scan(tempDir.resolve("missing")));
  }

  // --- preferredJarOf() ---

  @Test
  void copyShippedAlongsideTheArtifactWinsOverANewerOneElsewhere() throws IOException {
    // The versions ES 9.5.0 ships: commons-codec 1.15 in modules/reindex, 1.19.0 in
    // ingest-attachment.
    Path distribution =
        distributionWith(
            "modules/reindex/elasticsearch-rest-client-9.5.0.jar",
            "modules/reindex/commons-codec-1.15.jar",
            "modules/ingest-attachment/commons-codec-1.19.0.jar");
    EsDistribution scanned = EsDistribution.scan(distribution);

    Optional<BundledJar> codec =
        scanned.preferredJarOf(
            "commons-codec", scanned.directoriesShipping("elasticsearch-rest-client"));

    assertEquals("1.15", codec.orElseThrow().version());
  }

  @Test
  void libCopyWinsWhenNoneIsShippedAlongsideTheArtifact() throws IOException {
    Path distribution =
        distributionWith(
            "lib/log4j-api-2.26.1.jar", "modules/ingest-attachment/log4j-api-2.20.0.jar");
    EsDistribution scanned = EsDistribution.scan(distribution);

    assertEquals(
        "2.26.1",
        scanned
            .preferredJarOf("log4j-api", Set.of(tempDir.resolve("es/modules/reindex")))
            .orElseThrow()
            .version());
  }

  @Test
  void newestCopyWinsWhenNeitherAlongsideNorInLib() throws IOException {
    Path distribution =
        distributionWith("modules/a/commons-codec-1.15.jar", "modules/b/commons-codec-1.19.0.jar");

    assertEquals(
        "1.19.0",
        EsDistribution.scan(distribution)
            .preferredJarOf("commons-codec", Set.of())
            .orElseThrow()
            .version());
  }

  @Test
  void selectionIsStableAcrossScansWhenVersionsAreEqual() throws IOException {
    Path distribution =
        distributionWith(
            "modules/b/elasticsearch-ssl-config-9.5.0.jar",
            "modules/a/elasticsearch-ssl-config-9.5.0.jar");
    EsDistribution scanned = EsDistribution.scan(distribution);

    Path first = scanned.preferredJarOf("elasticsearch-ssl-config", Set.of()).orElseThrow().file();
    Path second =
        EsDistribution.scan(distribution)
            .preferredJarOf("elasticsearch-ssl-config", Set.of())
            .orElseThrow()
            .file();

    assertEquals(first, second);
  }

  @Test
  void newestCopyIsChosenByVersionOrderNotFileName() throws IOException {
    Path distribution =
        distributionWith(
            "modules/a/netty-buffer-4.1.9.Final.jar", "modules/b/netty-buffer-4.1.10.Final.jar");

    assertEquals(
        "4.1.10.Final",
        EsDistribution.scan(distribution)
            .preferredJarOf("netty-buffer", Set.of())
            .orElseThrow()
            .version());
  }

  // --- download and unpack layout ---

  @Test
  void downloadUrlNamesTheLinuxArchiveOfTheVersion() {
    assertEquals(
        "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-9.5.0-linux-x86_64.tar.gz",
        EsDistribution.downloadUrl("9.5.0"));
  }

  @Test
  void theArchiveIsUnpackedIntoADirectoryNamedForTheVersion() {
    assertEquals(tempDir.resolve("es-9.5.0.tar.gz"), EsDistribution.archiveIn(tempDir, "9.5.0"));
    assertEquals(tempDir.resolve("es-9.5.0"), EsDistribution.unpackDirIn(tempDir, "9.5.0"));
    assertEquals(
        tempDir.resolve("es-9.5.0/elasticsearch-9.5.0"),
        EsDistribution.distributionDirIn(tempDir, "9.5.0"));
  }

  private Path distributionWith(String... relativeJarPaths) throws IOException {
    Path distribution = tempDir.resolve("es");
    for (String relativePath : relativeJarPaths) {
      Path jar = distribution.resolve(relativePath);
      Files.createDirectories(jar.getParent());
      Files.writeString(jar, "");
    }
    Files.createDirectories(distribution.resolve("lib"));
    return distribution;
  }
}
