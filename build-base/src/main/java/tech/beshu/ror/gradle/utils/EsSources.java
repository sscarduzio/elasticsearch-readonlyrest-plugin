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

import org.gradle.api.GradleException;
import org.w3c.dom.Element;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The ES sources at the tag of the release being mirrored, read for the coordinates of the third-party jars
 * the distribution ships.
 *
 * <p>Elastic's dependency verification metadata lists the group, name and version of every third-party
 * artifact their build resolves, which is a superset of what ends up in a distribution. It is read rather than
 * the build scripts because its schema is Gradle's rather than Elastic's, so it does not move when they
 * reorganise the build; and it is read at the release's own tag, so a dependency that release added is
 * described by it.
 *
 * <p>Elastic's own artifacts are not in it -- they are projects of that build, not dependencies it resolves --
 * and are placed by {@link EsGroups} instead.
 */
public final class EsSources implements ArtifactGroups {

  private static final String SOURCES_URL =
      "https://raw.githubusercontent.com/elastic/elasticsearch";
  private static final String VERIFICATION_METADATA = "gradle/verification-metadata.xml";

  private final String url;
  private Map<String, String> groupsByArtifact;

  private EsSources(String url) {
    this.url = url;
  }

  /** The sources of an ES release, which Elastic tags {@code v<version>}. */
  public static EsSources forVersion(String esVersion) {
    return forVersion(esVersion, SOURCES_URL);
  }

  static EsSources forVersion(String esVersion, String sourcesUrl) {
    return new EsSources(
        sourcesUrl.replaceAll("/+$", "") + "/v" + esVersion + "/" + VERIFICATION_METADATA);
  }

  /**
   * The group the release resolves {@code artifactId} at {@code version} from. Both are needed: a build can
   * resolve two versions of one artifact, and the distribution says which of them it ships.
   */
  @Override
  public Optional<String> groupOf(String artifactId, String version) {
    return Optional.ofNullable(components().get(artifactId + ":" + version));
  }

  /** Read once, and only when something actually has to be looked up. */
  private Map<String, String> components() {
    if (groupsByArtifact == null) {
      groupsByArtifact = readComponents();
    }
    return groupsByArtifact;
  }

  private Map<String, String> readComponents() {
    Element metadata = XmlDocuments.rootOf(Downloads.read(url), url);
    Map<String, String> groups = new HashMap<>();
    for (Element components : XmlDocuments.childrenNamed(metadata, "components")) {
      for (Element component : XmlDocuments.childrenNamed(components, "component")) {
        groups.putIfAbsent(
            component.getAttribute("name") + ":" + component.getAttribute("version"),
            component.getAttribute("group"));
      }
    }
    if (groups.isEmpty()) {
      throw new GradleException("No <components><component> in " + url);
    }
    return Map.copyOf(groups);
  }
}
