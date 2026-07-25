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

import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;

/**
 * Single source of truth for a ROR plugin deliverable's coordinates, artifact name and location. The name
 * format ({@code <pluginName>-<pluginVersion>_es<esVersion>}) is shared by the readonlyrest.* convention
 * plugins for the fat jar, the plugin zip and the base/derived deliverables, so it is defined here once.
 * {@code pluginName} / {@code pluginVersion} come from the root project's gradle properties.
 */
public final class PluginArtifact {

  private PluginArtifact() {}

  public static String name(Project project) {
    return String.valueOf(project.getRootProject().property("pluginName"));
  }

  public static String version(Project project) {
    return String.valueOf(project.getRootProject().property("pluginVersion"));
  }

  /** e.g. {@code readonlyrest-1.70.2_es8.3.0}. */
  public static String fullName(Project project, String esVersion) {
    return name(project) + "-" + version(project) + "_es" + esVersion;
  }

  /** {@code build/distributions} -- where built plugin zips (deliverables) are written. */
  public static Provider<Directory> distributionsDir(Project project) {
    return project.getLayout().getBuildDirectory().dir("distributions");
  }

  /** The deliverable zip for a given ES version, inside {@link #distributionsDir}. */
  public static Provider<RegularFile> zip(Project project, String esVersion) {
    return distributionsDir(project).map(dir -> dir.file(fullName(project, esVersion) + ".zip"));
  }
}
