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
import tech.beshu.ror.gradle.utils.MavenPoms.Coordinate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class MavenRepositoryTest {

  private static final Coordinate ELASTICSEARCH =
      new Coordinate("org.elasticsearch", "elasticsearch");

  @TempDir Path tempDir;

  // --- publishedVersions() ---

  @Test
  void readsTheVersionsInTheOrderTheMetadataListsThem() throws IOException {
    publish(
        "org/elasticsearch/elasticsearch/maven-metadata.xml",
        metadataListing("9.3.8", "9.4.4", "9.5.0"));

    assertEquals(List.of("9.3.8", "9.4.4", "9.5.0"), repository().publishedVersions(ELASTICSEARCH));
  }

  @Test
  void unreadableMetadataThrowsNamingTheUrl() {
    GradleException failure =
        assertThrows(GradleException.class, () -> repository().publishedVersions(ELASTICSEARCH));

    assertTrue(failure.getMessage().contains("org/elasticsearch/elasticsearch/maven-metadata.xml"));
  }

  @Test
  void metadataWithoutVersionsThrows() throws IOException {
    publish(
        "org/elasticsearch/elasticsearch/maven-metadata.xml",
        "<metadata><artifactId>elasticsearch</artifactId></metadata>");

    assertThrows(GradleException.class, () -> repository().publishedVersions(ELASTICSEARCH));
  }

  // --- pomOf() ---

  @Test
  void readsThePomOfOneVersion() throws IOException {
    publish("org/elasticsearch/elasticsearch/9.4.4/elasticsearch-9.4.4.pom", "<project/>");

    assertEquals("<project/>", repository().pomOf(ELASTICSEARCH, "9.4.4"));
  }

  @Test
  void unpublishedPomThrowsNamingTheUrl() {
    GradleException failure =
        assertThrows(GradleException.class, () -> repository().pomOf(ELASTICSEARCH, "9.4.4"));

    assertTrue(failure.getMessage().contains("elasticsearch-9.4.4.pom"));
  }

  // --- base url ---

  @Test
  void trailingSlashesInTheBaseUrlAreIgnored() throws IOException {
    publish("org/elasticsearch/elasticsearch/9.4.4/elasticsearch-9.4.4.pom", "<project/>");

    assertEquals(
        "<project/>", MavenRepository.at(tempDir.toUri() + "//").pomOf(ELASTICSEARCH, "9.4.4"));
  }

  private MavenRepository repository() {
    return MavenRepository.at(tempDir.toUri().toString());
  }

  private void publish(String relativePath, String content) throws IOException {
    Path file = tempDir.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static String metadataListing(String... versions) {
    StringBuilder metadata = new StringBuilder("<metadata><versioning><versions>");
    for (String version : versions) {
      metadata.append("<version>").append(version).append("</version>");
    }
    return metadata.append("</versions></versioning></metadata>").toString();
  }
}
