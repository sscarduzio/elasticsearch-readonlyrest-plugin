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
import tech.beshu.ror.gradle.utils.MavenPoms.Coordinate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Reads what a Maven repository publishes: the versions of an artifact, and the POM of one version. */
public final class MavenRepository {

  private static final String MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2";
  private static final int TIMEOUT_MS = 30_000;

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

  /** The versions of {@code coordinate} the repository publishes, in the order its metadata lists them. */
  public List<String> publishedVersions(Coordinate coordinate) {
    String url = baseUrl + "/" + coordinate.repositoryPath() + "/maven-metadata.xml";
    Element metadata = Xml.rootOf(read(url), url);
    return Xml.childNamed(metadata, "versioning")
        .flatMap(versioning -> Xml.childNamed(versioning, "versions"))
        .map(versions -> Xml.childrenNamed(versions, "version").stream().map(Xml::textOf).toList())
        .orElseThrow(() -> new GradleException("No <versioning><versions> in " + url));
  }

  /** The POM the repository publishes for one version of {@code coordinate}. */
  public String pomOf(Coordinate coordinate, String version) {
    return read(
        baseUrl + "/" + coordinate.repositoryPath(version) + "/" + coordinate.pomFileName(version));
  }

  private static String read(String url) {
    try {
      URLConnection connection = URI.create(url).toURL().openConnection();
      connection.setConnectTimeout(TIMEOUT_MS);
      connection.setReadTimeout(TIMEOUT_MS);
      try (InputStream content = connection.getInputStream()) {
        return new String(content.readAllBytes(), StandardCharsets.UTF_8);
      }
    } catch (IOException | IllegalArgumentException e) {
      throw new GradleException("Cannot read " + url + ": " + e, e);
    }
  }
}
