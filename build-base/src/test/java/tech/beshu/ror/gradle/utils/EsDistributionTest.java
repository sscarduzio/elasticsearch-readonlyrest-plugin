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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;

class EsDistributionTest {

  private static final String ARCHIVE_NAME = "elasticsearch-9.5.0-linux-x86_64.tar.gz";

  @TempDir Path tempDir;

  // --- scan() ---

  @Test
  void indexesJarsFromLibAndEveryModuleDirectory() throws IOException {
    Path distribution =
        distributionWith(
            "lib/elasticsearch-9.5.0.jar", "modules/reindex/elasticsearch-rest-client-9.5.0.jar");

    EsDistribution scanned = EsDistribution.scan(distribution);

    assertEquals("9.5.0", scanned.jarsOf("elasticsearch").get(0).version());
    assertEquals("9.5.0", scanned.jarsOf("elasticsearch-rest-client").get(0).version());
  }

  @Test
  void parsesVersionsWithNonNumericSegments() throws IOException {
    Path distribution = distributionWith("modules/transport-netty4/netty-buffer-4.1.135.Final.jar");

    BundledJar netty = EsDistribution.scan(distribution).jarsOf("netty-buffer").get(0);

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

    assertTrue(EsDistribution.scan(distribution).jarsOf("nope").isEmpty());
  }

  @Test
  void scanningSomethingThatIsNotADistributionThrows() {
    assertThrows(GradleException.class, () -> EsDistribution.scan(tempDir.resolve("missing")));
  }

  // --- classpathDirOf() ---

  @Test
  void theServerIsShippedWithItsClasspathInLib() throws IOException {
    Path distribution =
        distributionWith("lib/elasticsearch-9.5.0.jar", "lib/lucene-core-10.5.0.jar");

    EsDistribution scanned = EsDistribution.scan(distribution);

    assertEquals(
        distribution.resolve("lib"), scanned.classpathDirOf("elasticsearch").orElseThrow());
    assertEquals(2, scanned.jarsIn(distribution.resolve("lib")).size());
  }

  @Test
  void aModuleTakesTheDirectoryNamedAfterIt() throws IOException {
    // ES 9.5.0 ships transport-netty4 in three modules; only one of them is its own.
    Path distribution =
        distributionWith(
            "modules/repository-azure/transport-netty4-9.5.0.jar",
            "modules/x-pack-security/transport-netty4-9.5.0.jar",
            "modules/transport-netty4/transport-netty4-9.5.0.jar",
            "modules/transport-netty4/netty-buffer-4.1.135.Final.jar");

    assertEquals(
        distribution.resolve("modules/transport-netty4"),
        EsDistribution.scan(distribution).classpathDirOf("transport-netty4").orElseThrow());
  }

  @Test
  void anArtifactWithoutADirectoryOfItsOwnTakesTheFullestOne() throws IOException {
    // Only modules/reindex ships the HTTP client jars the rest client needs alongside it.
    Path distribution =
        distributionWith(
            "modules/x-pack-enrich/elasticsearch-rest-client-9.5.0.jar",
            "modules/reindex/elasticsearch-rest-client-9.5.0.jar",
            "modules/reindex/httpclient-4.5.14.jar",
            "modules/reindex/httpcore-4.4.16.jar");

    assertEquals(
        distribution.resolve("modules/reindex"),
        EsDistribution.scan(distribution)
            .classpathDirOf("elasticsearch-rest-client")
            .orElseThrow());
  }

  @Test
  void theDirectoryIsTheSameAcrossScansWhenTwoAreEquallyFull() throws IOException {
    Path distribution =
        distributionWith(
            "modules/b/elasticsearch-ssl-config-9.5.0.jar",
            "modules/a/elasticsearch-ssl-config-9.5.0.jar");

    assertEquals(
        EsDistribution.scan(distribution).classpathDirOf("elasticsearch-ssl-config"),
        EsDistribution.scan(distribution).classpathDirOf("elasticsearch-ssl-config"));
  }

  @Test
  void anArtifactTheDistributionDoesNotShipHasNoClasspathDirectory() throws IOException {
    Path distribution = distributionWith("lib/elasticsearch-9.5.0.jar");

    assertTrue(EsDistribution.scan(distribution).classpathDirOf("nope").isEmpty());
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

  // --- downloadArchiveTo() ---

  @Test
  void theArchiveIsKeptOnceItsChecksumMatches() throws IOException {
    publishArchive("an ES tarball");

    Path archive = EsDistribution.downloadArchiveTo(buildDir(), "9.5.0", downloadsUrl());

    assertEquals(EsDistribution.archiveIn(buildDir(), "9.5.0"), archive);
    assertEquals("an ES tarball", Files.readString(archive));
  }

  @Test
  void aChecksumThatDoesNotMatchLeavesNoArchiveBehind() throws IOException {
    publishArchive("an ES tarball", sha512Of("something else entirely"));

    GradleException failure =
        assertThrows(
            GradleException.class,
            () -> EsDistribution.downloadArchiveTo(buildDir(), "9.5.0", downloadsUrl()));

    assertTrue(failure.getMessage().contains("Checksum mismatch"));
    assertTrue(Files.notExists(EsDistribution.archiveIn(buildDir(), "9.5.0")));
    assertTrue(nothingLeftInBuildDir());
  }

  @Test
  void anInterruptedDownloadLeavesNoArchiveBehind() throws IOException {
    // The checksum is published but the archive is not, so the download fails part way through.
    publishChecksum(sha512Of("an ES tarball"));

    GradleException failure =
        assertThrows(
            GradleException.class,
            () -> EsDistribution.downloadArchiveTo(buildDir(), "9.5.0", downloadsUrl()));

    assertTrue(failure.getMessage().contains("elasticsearch-9.5.0-linux-x86_64.tar.gz"));
    assertTrue(Files.notExists(EsDistribution.archiveIn(buildDir(), "9.5.0")));
    assertTrue(nothingLeftInBuildDir());
  }

  @Test
  void anArchiveAlreadyInTheBuildDirIsNotDownloadedAgain() throws IOException {
    // Only the checksum is published, so downloading the archive again would fail.
    publishChecksum(sha512Of("downloaded earlier"));
    Files.createDirectories(buildDir());
    Files.writeString(EsDistribution.archiveIn(buildDir(), "9.5.0"), "downloaded earlier");

    assertEquals(
        "downloaded earlier",
        Files.readString(EsDistribution.downloadArchiveTo(buildDir(), "9.5.0", downloadsUrl())));
  }

  @Test
  void anArchiveAlreadyInTheBuildDirIsCheckedBeforeItIsReused() throws IOException {
    // What an older build that did not verify its downloads could have left behind.
    publishArchive("an ES tarball");
    Path archive = EsDistribution.archiveIn(buildDir(), "9.5.0");
    Files.createDirectories(buildDir());
    Files.writeString(archive, "half an ES tarball");

    GradleException failure =
        assertThrows(
            GradleException.class,
            () -> EsDistribution.downloadArchiveTo(buildDir(), "9.5.0", downloadsUrl()));

    assertTrue(failure.getMessage().contains("Checksum mismatch"));
    assertTrue(failure.getMessage().contains(archive.toString()));
  }

  @Test
  void aChecksumFileThatNamesNoHashThrows() throws IOException {
    publishArchive("an ES tarball", "<html>404</html>");

    GradleException failure =
        assertThrows(
            GradleException.class,
            () -> EsDistribution.downloadArchiveTo(buildDir(), "9.5.0", downloadsUrl()));

    assertTrue(failure.getMessage().contains("Not a SHA-512 checksum"));
  }

  private Path buildDir() {
    return tempDir.resolve("build");
  }

  private String downloadsUrl() {
    return publishedDir().toUri().toString();
  }

  /** Elastic publishes the archive and, beside it, a {@code .sha512} naming the hash and the file. */
  private void publishArchive(String content) throws IOException {
    publishArchive(content, sha512Of(content));
  }

  private void publishArchive(String content, String checksum) throws IOException {
    Path published = publishedDir().resolve(ARCHIVE_NAME);
    Files.createDirectories(published.getParent());
    Files.writeString(published, content);
    publishChecksum(checksum);
  }

  private void publishChecksum(String checksum) throws IOException {
    Path published = publishedDir().resolve(ARCHIVE_NAME + ".sha512");
    Files.createDirectories(published.getParent());
    Files.writeString(published, checksum + "  " + ARCHIVE_NAME);
  }

  private Path publishedDir() {
    return tempDir.resolve("downloads");
  }

  private static String sha512Of(String content) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-512").digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean nothingLeftInBuildDir() throws IOException {
    try (Stream<Path> entries = Files.list(buildDir())) {
      return entries.findAny().isEmpty();
    }
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
