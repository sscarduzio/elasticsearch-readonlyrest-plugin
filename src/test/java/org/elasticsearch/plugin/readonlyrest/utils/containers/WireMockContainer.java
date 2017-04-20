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

import com.google.common.collect.Lists;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.WaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class WireMockContainer extends GenericContainer<WireMockContainer> {

  private static Logger logger = Logger.getLogger(WireMockContainer.class.getName());

  private static int WIRE_MOCK_PORT = 8080;
  private static Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofSeconds(240);

  private WireMockContainer(ImageFromDockerfile imageFromDockerfile) {
    super(imageFromDockerfile);
  }

  public static WireMockContainer create(String... mappings) {
    ImageFromDockerfile dockerfile = new ImageFromDockerfile();
    List<File> mappingFiles = Lists.newArrayList(mappings).stream()
                                   .map(ContainerUtils::getResourceFile)
                                   .collect(Collectors.toList());
    mappingFiles.forEach(mappingFile -> dockerfile.withFileFromFile(mappingFile.getName(), mappingFile));
    logger.info("Creating WireMock container ...");
    WireMockContainer container = new WireMockContainer(
        dockerfile.withDockerfileFromBuilder(builder -> {
          DockerfileBuilder b = builder.from("rodolpheche/wiremock:2.5.1");
          mappingFiles.forEach(mappingFile -> b.copy(mappingFile.getName(), "/home/wiremock/mappings/"));
          b.build();
        }));
    return container
        .withExposedPorts(WIRE_MOCK_PORT)
        .waitingFor(
            container.waitStrategy()
                     .withStartupTimeout(CONTAINER_STARTUP_TIMEOUT)
        );
  }

  public String getWireMockHost() {
    return this.getContainerIpAddress();
  }

  public Integer getWireMockPort() {
    return this.getMappedPort(WIRE_MOCK_PORT);
  }

  private RestClient getClient() {
    return RestClient.builder(new HttpHost(getWireMockHost(), getWireMockPort())).build();
  }

  private WaitStrategy waitStrategy() {
    return new WaitWithRetriesStrategy("WireMock") {

      @Override
      protected boolean isReady() {
        try {
          return getClient()
              .performRequest("GET", "/__admin/")
              .getStatusLine().getStatusCode() == 200;
        } catch (IOException e) {
          return false;
        }
      }
    };
  }
}
