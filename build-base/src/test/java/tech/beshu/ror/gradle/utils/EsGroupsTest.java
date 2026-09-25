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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class EsGroupsTest {

  @TempDir Path tempDir;

  @Test
  void readsTheGroupPublishingTheArtifact() throws IOException {
    publish("org.elasticsearch.client", "elasticsearch-rest-client", "9.4.4", "9.5.0");

    assertEquals(
        "org.elasticsearch.client",
        groups().groupOf("elasticsearch-rest-client", "9.5.0").orElseThrow());
  }

  @Test
  void anArtifactThatMovedGroupIsPlacedWhereItWasAtThatVersion() throws IOException {
    // Elastic published elasticsearch-plugin-api under org.elasticsearch up to 8.6.2, and under
    // org.elasticsearch.plugin from 8.7 on.
    publish("org.elasticsearch", "elasticsearch-plugin-api", "8.6.0", "8.6.1", "8.6.2");
    publish("org.elasticsearch.plugin", "elasticsearch-plugin-api", "8.7.0", "9.4.4");

    EsGroups groups = groups();

    assertEquals(
        "org.elasticsearch", groups.groupOf("elasticsearch-plugin-api", "8.6.2").orElseThrow());
    assertEquals(
        "org.elasticsearch.plugin",
        groups.groupOf("elasticsearch-plugin-api", "9.5.0").orElseThrow());
  }

  @Test
  void versionsAboveTheOneAskedAboutDoNotPlaceIt() throws IOException {
    // A group the artifact only moved to later says nothing about where it was at 8.6.2.
    publish("org.elasticsearch", "elasticsearch-plugin-api", "8.6.2");
    publish("org.elasticsearch.plugin", "elasticsearch-plugin-api", "9.5.0");

    assertEquals(
        "org.elasticsearch", groups().groupOf("elasticsearch-plugin-api", "8.6.2").orElseThrow());
  }

  @Test
  void anArtifactNoneOfElasticsGroupsPublishesIsEmpty() throws IOException {
    // elasticsearch-log4j is shipped in the distribution and published nowhere.
    publish("org.elasticsearch", "elasticsearch-core", "9.5.0");

    assertTrue(groups().groupOf("elasticsearch-log4j", "9.5.0").isEmpty());
  }

  @Test
  void aGroupThatOnlyPublishesTheArtifactAfterTheReleaseIsNotUsed() throws IOException {
    publish("org.elasticsearch", "elasticsearch-plugin-api", "9.6.0");

    assertTrue(groups().groupOf("elasticsearch-plugin-api", "9.5.0").isEmpty());
  }

  private EsGroups groups() {
    return EsGroups.publishedIn(MavenRepository.at(tempDir.toUri().toString()));
  }

  private void publish(String groupId, String artifactId, String... versions) throws IOException {
    Path metadata =
        tempDir.resolve(
            new MavenCoordinate(groupId, artifactId).repositoryPath() + "/maven-metadata.xml");
    Files.createDirectories(metadata.getParent());
    StringBuilder listing = new StringBuilder("<metadata><versioning><versions>");
    for (String version : versions) {
      listing.append("<version>").append(version).append("</version>");
    }
    Files.writeString(metadata, listing.append("</versions></versioning></metadata>").toString());
  }
}
