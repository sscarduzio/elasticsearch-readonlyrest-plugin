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

/**
 * Where an artifact is published, without the version: Maven coordinates minus the V. The methods that need a
 * version take it as an argument, so one coordinate serves every version of the artifact.
 */
public record MavenCoordinate(String groupId, String artifactId) {

  /** The artifact's directory in a Maven repository layout, without the version. */
  public String repositoryPath() {
    return groupId.replace('.', '/') + "/" + artifactId;
  }

  /** The directory one version of the artifact lives in, in a Maven repository layout. */
  public String repositoryPath(String version) {
    return repositoryPath() + "/" + version;
  }

  public String pomFileName(String version) {
    return artifactId + "-" + version + ".pom";
  }
}
