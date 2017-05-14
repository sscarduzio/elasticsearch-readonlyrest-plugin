package org.elasticsearch.plugin.readonlyrest.integration;

import com.google.common.collect.Lists;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.utils.gradle.RorPluginGradleProject.getRootProject;

@RunWith(Parameterized.class)
public abstract class BaseIntegrationTests {

  @Parameterized.Parameters(name = "{0}")
  public static Collection<String> ESSpecificVersions() {
    return Optional.ofNullable(getRootProject().listFiles())
        .map(Lists::newArrayList)
        .orElse(Lists.newArrayList())
        .stream()
        .filter(File::isDirectory)
        .map(File::getName)
        .filter(s -> s.startsWith("es"))
        .collect(Collectors.toList());
  }

}
