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

import java.util.Arrays;
import java.util.Optional;

/**
 * Answers the one thing a jar file cannot: the Maven group it is published under. The file name gives the
 * artifact id and the version, and nothing inside it has to say where it came from.
 */
@FunctionalInterface
public interface ArtifactGroups {

  /** Empty when the source has nothing to say about this artifact, which is not an error by itself. */
  Optional<String> groupOf(String artifactId, String version);

  /**
   * What places the jars of one ES release: the sources of that release for everything they resolve from
   * elsewhere, Maven Central for Elastic's own, and the release itself for what Elastic ships but publishes
   * nowhere.
   */
  static ArtifactGroups ofEsRelease(String esVersion) {
    return firstOf(
        EsSources.forVersion(esVersion),
        EsGroups.publishedIn(MavenRepository.mavenCentral()),
        EsGroups.shippedWith(esVersion));
  }

  /** The first of {@code sources} with an answer; the ones after it are not asked. */
  static ArtifactGroups firstOf(ArtifactGroups... sources) {
    return (artifactId, version) ->
        Arrays.stream(sources)
            .map(source -> source.groupOf(artifactId, version))
            .flatMap(Optional::stream)
            .findFirst();
  }
}
