package org.elasticsearch.plugin.readonlyrest.utils.gradle;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

import java.io.File;
import java.nio.file.Paths;
import java.util.Optional;

public class RorPluginGradleProject {

  private final GradleProperties properties;
  private final File project;
  private final String name;

  public RorPluginGradleProject(String name) {
    this.name = name;
    this.project = esProject(name);
    this.properties = GradleProperties.create(project).orElseThrow(() ->
        new IllegalStateException("cannot load gradle.properties file")
    );
  }

  public static File getRootProject() {
    String projectDir = System.getProperty("project.dir");
    return projectDir != null ? Paths.get(projectDir).toFile() : new File("../");
  }

  public Optional<File> assemble() {
    runTask(name + ":assemble");
    File plugin = new File(project, "build/distributions/" + pluginName());
    if (!plugin.exists()) return Optional.empty();
    return Optional.of(plugin);
  }

  public GradleProperties getProperties() {
    return properties;
  }

  private File esProject(String esProjectName) {
    return new File(getRootProject(), esProjectName);
  }

  private String pluginName() {
    return String.format(
        "%s-%s_es%s.zip",
        properties.getProperty("pluginName"),
        properties.getProperty("pluginVersion"),
        properties.getProperty("esVersion")
    );
  }

  private void runTask(String task) {
    GradleConnector connector = GradleConnector.newConnector().forProjectDirectory(getRootProject());
    ProjectConnection connect = null;
    try {
      connect = connector.connect();
      connect.newBuild().forTasks(task).run();
    } finally {
      if (connect != null) connect.close();
    }
  }
}
