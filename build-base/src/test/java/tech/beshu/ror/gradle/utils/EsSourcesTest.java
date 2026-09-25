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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class EsSourcesTest {

  @TempDir Path tempDir;

  @Test
  void readsTheGroupTheReleaseResolvesAnArtifactFrom() throws IOException {
    publishVerificationMetadata("9.5.0", component("org.apache.lucene", "lucene-core", "10.5.0"));

    assertEquals(
        "org.apache.lucene", sourcesOf("9.5.0").groupOf("lucene-core", "10.5.0").orElseThrow());
  }

  @Test
  void tellsApartTwoVersionsOfOneArtifact() throws IOException {
    // ES 9.5.0 resolves both, and only the distribution says which of them it ships.
    publishVerificationMetadata(
        "9.5.0",
        component("net.sf.jopt-simple", "jopt-simple", "5.0.2")
            + component("org.other", "jopt-simple", "5.0.4"));

    EsSources sources = sourcesOf("9.5.0");

    assertEquals("net.sf.jopt-simple", sources.groupOf("jopt-simple", "5.0.2").orElseThrow());
    assertEquals("org.other", sources.groupOf("jopt-simple", "5.0.4").orElseThrow());
  }

  @Test
  void anArtifactTheReleaseDoesNotResolveIsEmpty() throws IOException {
    publishVerificationMetadata("9.5.0", component("org.apache.lucene", "lucene-core", "10.5.0"));

    assertTrue(sourcesOf("9.5.0").groupOf("elasticsearch-core", "9.5.0").isEmpty());
  }

  @Test
  void theSourcesOfAnotherReleaseAreNotRead() throws IOException {
    publishVerificationMetadata("9.4.4", component("org.apache.lucene", "lucene-core", "10.4.0"));

    assertThrows(GradleException.class, () -> sourcesOf("9.5.0").groupOf("lucene-core", "10.4.0"));
  }

  @Test
  void metadataListingNoComponentThrows() throws IOException {
    publishVerificationMetadata("9.5.0", "");

    GradleException failure =
        assertThrows(GradleException.class, () -> sourcesOf("9.5.0").groupOf("anything", "1.0"));

    assertTrue(failure.getMessage().contains("verification-metadata.xml"));
  }

  private EsSources sourcesOf(String esVersion) {
    return EsSources.forVersion(esVersion, tempDir.toUri().toString());
  }

  /** The sources are addressed by tag, which is how Elastic publishes a release. */
  private void publishVerificationMetadata(String esVersion, String components) throws IOException {
    Path metadata = tempDir.resolve("v" + esVersion + "/gradle/verification-metadata.xml");
    Files.createDirectories(metadata.getParent());
    Files.writeString(
        metadata,
        "<verification-metadata><components>"
            + components
            + "</components></verification-metadata>");
  }

  private static String component(String groupId, String artifactId, String version) {
    return "<component group=\""
        + groupId
        + "\" name=\""
        + artifactId
        + "\" version=\""
        + version
        + "\"/>";
  }
}
