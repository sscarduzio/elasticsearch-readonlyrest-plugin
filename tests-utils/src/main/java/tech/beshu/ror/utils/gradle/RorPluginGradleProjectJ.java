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
package tech.beshu.ror.utils.gradle;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

import java.io.File;
import java.nio.file.Paths;
import java.util.Optional;

public class RorPluginGradleProjectJ {

  private final GradleProperties esProjectProperties;
  private final GradleProperties rootProjectProperties;
  private final File project;
  private final String name;

  public static RorPluginGradleProjectJ fromSystemProperty() {
    return Optional.ofNullable(System.getProperty("esModule"))
        .map(RorPluginGradleProjectJ::new)
        .orElseThrow(() -> new IllegalStateException("No 'esModule' system property set"));
  }

  public RorPluginGradleProjectJ(String name) {
    this.name = name;
    this.project = esProject(name);
    this.esProjectProperties = GradleProperties.create(project).orElseThrow(() ->
        new IllegalStateException("cannot load '" + name + "' project gradle.properties file")
    );
    this.rootProjectProperties = GradleProperties.create(getRootProject()).orElseThrow(() ->
        new IllegalStateException("cannot load root project gradle.properties file")
    );
  }

  public static File getRootProject() {
    String projectDir = System.getProperty("project.dir");
    return projectDir != null ? Paths.get(projectDir).toFile() : new File("../");
  }

  public Optional<File> assemble() {
    runTask(name + ":ror");
    File plugin = new File(project, "build/distributions/" + pluginName());
    if (!plugin.exists()) return Optional.empty();
    return Optional.of(plugin);
  }

  public String getESVersion() {
    return esProjectProperties.getProperty("esVersion");
  }

  private File esProject(String esProjectName) {
    return new File(getRootProject(), esProjectName);
  }

  private String pluginName() {
    return String.format(
        "%s-%s_es%s.zip",
        rootProjectProperties.getProperty("pluginName"),
        rootProjectProperties.getProperty("pluginVersion"),
        getESVersion()
    );
  }

  private void runTask(String task) {
    GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(getRootProject());
    ProjectConnection connect = null;
    try {
      connect = connector.connect();
      connect.newBuild().forTasks(task).run();
    }
    finally {
      if (connect != null) connect.close();
    }
  }
}
