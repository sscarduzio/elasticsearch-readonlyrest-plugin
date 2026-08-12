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

import java.util.List;
import java.util.Optional;

/** Reads what a Maven repository publishes: the versions of an artifact. */
public final class MavenRepository {

  private static final String MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2";

  private final String baseUrl;

  private MavenRepository(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public static MavenRepository mavenCentral() {
    return at(MAVEN_CENTRAL_URL);
  }

  /** @param baseUrl the repository root, for instance {@code https://repo1.maven.org/maven2} */
  public static MavenRepository at(String baseUrl) {
    return new MavenRepository(baseUrl.replaceAll("/+$", ""));
  }

  /**
   * The versions of {@code coordinate} the repository publishes, in the order its metadata lists them, and
   * empty when it publishes the artifact under no version at all -- which is how an artifact that lives under
   * a different group reads. Only a missing metadata file reads as empty; an unreachable repository still
   * throws, so a network problem is never taken for an artifact nobody published.
   */
  public List<String> publishedVersions(MavenCoordinate coordinate) {
    String url = baseUrl + "/" + coordinate.repositoryPath() + "/maven-metadata.xml";
    Optional<String> published = Downloads.find(url);
    if (published.isEmpty()) {
      return List.of();
    }
    Element metadata = XmlDocuments.rootOf(published.get(), url);
    return XmlDocuments.childNamed(metadata, "versioning")
        .flatMap(versioning -> XmlDocuments.childNamed(versioning, "versions"))
        .map(
            versions ->
                XmlDocuments.childrenNamed(versions, "version").stream()
                    .map(XmlDocuments::textOf)
                    .toList())
        .orElseThrow(() -> new GradleException("No <versioning><versions> in " + url));
  }
}
