package org.elasticsearch.plugin.readonlyrest.integration;


import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.junit.AfterClass;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.testcontainers.containers.GenericContainer;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.utils.gradle.RorPluginGradleProject.getRootProject;

@RunWith(Parameterized.class)
public abstract class BaseIntegrationTests<T extends GenericContainer<?>> {

  private static Map<String, GenericContainer<?>> containers = Maps.newHashMap();

  private final String currentEsProject;

  BaseIntegrationTests(String esProject) {
    this.currentEsProject = esProject;
  }

  @Parameterized.Parameters(name = "{0}")
  public static Collection<String> esSpecificVersions() {
    return Optional.ofNullable(getRootProject().listFiles())
        .map(Lists::newArrayList)
        .orElse(Lists.newArrayList())
        .stream()
        .filter(File::isDirectory)
        .map(File::getName)
        .filter(s -> s.startsWith("es"))
        .collect(Collectors.toList());
  }

  @SuppressWarnings("unchecked")
  protected T getContainer() {
    if(!containers.containsKey(currentEsProject)) {
      T newContainer = createContainer(currentEsProject);
      newContainer.start();
      containers.put(currentEsProject, newContainer);
    }
    return (T) containers.get(currentEsProject);
  }

  protected abstract T createContainer(String esProject);

  @AfterClass
  public static void teardown() {
    containers.values().forEach(GenericContainer::stop);
    containers = Maps.newHashMap();
  }
}

