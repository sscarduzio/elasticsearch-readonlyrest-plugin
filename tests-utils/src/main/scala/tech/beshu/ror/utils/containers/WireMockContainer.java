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

package tech.beshu.ror.utils.containers;

import com.google.common.collect.Lists;
import org.apache.http.client.methods.HttpGet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

public class WireMockContainer extends GenericContainer<WireMockContainer> {

  private static final Logger logger = LogManager.getLogger(WireMockContainer.class);

  public static int WIRE_MOCK_PORT = 8080;
  private static final Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofSeconds(240);

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
    WireMockContainer cont = container
        .withExposedPorts(WIRE_MOCK_PORT)
        .waitingFor(
            container.waitStrategy()
                     .withStartupTimeout(CONTAINER_STARTUP_TIMEOUT)
        );

    cont.setNetwork(Network.SHARED);
    return cont.withLogConsumer(s -> System.out.print("[WIREMOCK-ext-port:" + cont.getWireMockPort() + "] " + s.getUtf8String()));
  }

  public String getWireMockHost() {
    return this.getHost();
  }

  public Integer getWireMockPort() {
    return this.getMappedPort(WIRE_MOCK_PORT);
  }

  private RestClient getClient() {
    return new RestClient(false, getWireMockHost(), getWireMockPort());
  }

  private WaitStrategy waitStrategy() {
    return new WaitWithRetriesStrategy("WireMock") {

      @Override
      protected boolean isReady() {
        try {
          RestClient client = getClient();
          return client
              .execute(new HttpGet(client.from("/__admin/")))
              .getStatusLine().getStatusCode() == 200;
        } catch (Exception e) {
          return false;
        }
      }
    };
  }
}
