package org.elasticsearch.plugin.readonlyrest.utils.containers;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.HostPortWaitStrategy;
import org.testcontainers.containers.wait.WaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.logging.Logger;

public class WireMockContainer extends GenericContainer<WireMockContainer> {

  private static Logger logger = Logger.getLogger(WireMockContainer.class.getName());

  private static int WIRE_MOCK_PORT = 8080;
  private static Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofSeconds(60);

  private WireMockContainer(ImageFromDockerfile imageFromDockerfile) {
    super(imageFromDockerfile);
  }

  public static WireMockContainer create(String mappings) {
    File mappingsFile = ContainerUtils.getResourceFile(mappings);
    logger.info("Creating WireMock container ...");
    WireMockContainer container = new WireMockContainer(
        new ImageFromDockerfile()
            .withFileFromFile(mappingsFile.getName(), mappingsFile)
            .withDockerfileFromBuilder(builder -> builder
                .from("rodolpheche/wiremock:2.5.1")
                .copy(mappingsFile.getName(), "/home/wiremock/mappings/")
                .build()));
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
