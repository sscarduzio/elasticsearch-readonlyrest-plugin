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
package org.elasticsearch.plugin.readonlyrest.utils.containers;

import com.google.common.collect.ImmutableList;
import org.elasticsearch.plugin.readonlyrest.utils.containers.exceptions.ContainerCreationException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.regex.Pattern;

public class ESWithReadonlyRestContainerUtils {

  public static ESWithReadonlyRestContainer create(MultiContainer externalDependencies,
                                                   String elasticsearchConfig) {
    return create(externalDependencies, elasticsearchConfig, Optional.empty());
  }

  public static ESWithReadonlyRestContainer create(MultiContainer externalDependencies,
                                                   String elasticsearchConfig,
                                                   ESWithReadonlyRestContainer.ESInitalizer initalizer) {
    return create(externalDependencies, elasticsearchConfig, Optional.of(initalizer));
  }

  private static ESWithReadonlyRestContainer create(MultiContainer externalDependencies,
                                                   String elasticsearchConfig,
                                                   Optional<ESWithReadonlyRestContainer.ESInitalizer> initalizer) {
    File adjustedEsConfig = copyAndAdjustConfig(
      ContainerUtils.getResourceFile(elasticsearchConfig),
      createTempFile(),
      externalDependencies.containers()
    );
    ESWithReadonlyRestContainer esContainer = ESWithReadonlyRestContainer.create(adjustedEsConfig.getName(), initalizer);
    esContainer.start();
    return esContainer;
  }

  private static File createTempFile() {
    try {
      File newEsConfigFile = Files.createTempFile(ContainerUtils.getResourceFile("").toPath(), "tmp", ".tmp").toFile();
      newEsConfigFile.deleteOnExit();
      return newEsConfigFile;
    } catch (IOException e) {
      throw new ContainerCreationException("Cannot create elasticsearch config", e);
    }
  }

  private static File copyAndAdjustConfig(File sourceConfig,
                                          File destConfig,
                                          ImmutableList<MultiContainer.NamedContainer> externalDependencyContainers) {
    try {
      try (BufferedReader br = new BufferedReader(new FileReader(sourceConfig))) {
        try (FileWriter fw = new FileWriter(destConfig)) {
          String s;
          while ((s = br.readLine()) != null) {
            String replaced = s;
            for (MultiContainer.NamedContainer container : externalDependencyContainers) {
              if (container.getIpAddress().isPresent()) {
                replaced = replaced.replaceAll(
                  Pattern.compile("\\{" + container.getName() + "\\}").pattern(),
                  container.getIpAddress().get()
                );
              }
            }
            fw.write(replaced + "\n");
          }
        }
      }
    } catch (Exception e) {
      throw new ContainerCreationException("Cannot create elasticsearch config", e);
    }
    return destConfig;
  }

}
