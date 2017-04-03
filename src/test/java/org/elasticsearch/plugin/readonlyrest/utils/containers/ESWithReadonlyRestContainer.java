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

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.plugin.readonlyrest.utils.containers.exceptions.ContainerCreationException;
import org.elasticsearch.plugin.readonlyrest.utils.gradle.GradleProjectUtils;
import org.elasticsearch.plugin.readonlyrest.utils.gradle.GradleProperties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.WaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.shaded.com.fasterxml.jackson.core.type.TypeReference;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.elasticsearch.plugin.readonlyrest.utils.containers.ContainerUtils.checkTimeout;

public class ESWithReadonlyRestContainer extends GenericContainer<ESWithReadonlyRestContainer> {

  private static Logger logger = Logger.getLogger(ESWithReadonlyRestContainer.class.getName());

  private static int ES_PORT = 9200;
  private static Duration WAIT_BETWEEN_RETRIES = Duration.ofSeconds(1);
  private static Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofSeconds(60);
  private static String ADMIN_LOGIN = "admin";
  private static String ADMIN_PASSWORD = "container";

  private static GradleProperties properties =
    GradleProperties
      .create()
      .orElseThrow(() -> new ContainerCreationException("Cannot load gradle properties"));

  private ESWithReadonlyRestContainer(ImageFromDockerfile imageFromDockerfile) {
    super(imageFromDockerfile);
  }

  public static ESWithReadonlyRestContainer create(String elasticsearchConfig, ESInitalizer initalizer) {
    File config = ContainerUtils.getResourceFile(elasticsearchConfig);
    Optional<File> pluginFileOpt = GradleProjectUtils.assemble();
    if (!pluginFileOpt.isPresent()) {
      throw new ContainerCreationException("Plugin file assembly failed");
    }
    File pluginFile = pluginFileOpt.get();
    logger.info("Creating ES container ...");
    String elasticsearchConfigName = "elasticsearch.yml";
    ESWithReadonlyRestContainer container = new ESWithReadonlyRestContainer(
      new ImageFromDockerfile()
        .withFileFromFile(pluginFile.getName(), pluginFile)
        .withFileFromFile(elasticsearchConfigName, config)
        .withDockerfileFromBuilder(builder -> builder
          .from("docker.elastic.co/elasticsearch/elasticsearch:" + properties.getProperty("esVersion"))
          .copy(pluginFile.getName(), "/tmp/")
          .copy(elasticsearchConfigName, "/usr/share/elasticsearch/config/")
          .run("yes | /usr/share/elasticsearch/bin/elasticsearch-plugin install " +
                 "file:/tmp/" + pluginFile.getName())
          .build()));
    return container
      .withExposedPorts(ES_PORT)
      .waitingFor(container.waitStrategy(initalizer).withStartupTimeout(CONTAINER_STARTUP_TIMEOUT));
  }

  public String getESHost() {
    return this.getContainerIpAddress();
  }

  public Integer getESPort() {
    return this.getMappedPort(ES_PORT);
  }

  public RestClient getClient() {
    return clientBuilder().build();
  }

  public RestClient getClient(String name, String password) {
    return clientBuilder().setDefaultHeaders(new Header[]{authorizationHeader(name, password)}).build();
  }

  private RestClient getAdminClient() {
    return clientBuilder().setDefaultHeaders(new Header[]{authorizationHeader(ADMIN_LOGIN, ADMIN_PASSWORD)}).build();
  }

  private Header authorizationHeader(String name, String password) {
    String base64userPass = Base64.getEncoder().encodeToString((name + ":" + password).getBytes());
    return new BasicHeader("AsyncAuthorization", "Basic " + base64userPass);
  }

  private RestClientBuilder clientBuilder() {
    return RestClient.builder(new HttpHost(getESHost(), getESPort()));
  }

  private WaitStrategy waitStrategy(ESInitalizer initalizer) {
    final ObjectMapper mapper = new ObjectMapper();
    return new GenericContainer.AbstractWaitStrategy() {
      @Override
      protected void waitUntilReady() {
        logger.info("Waiting for ES container ...");
        final RestClient client = getAdminClient();
        final Instant startTime = Instant.now();
        while (!isReady(client) && !checkTimeout(startTime, startupTimeout)) {
          try {
            Thread.sleep(WAIT_BETWEEN_RETRIES.toMillis());
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
        initalizer.initialize(getAdminClient());
        logger.info("ES container stated");
      }

      private boolean isReady(RestClient client) {
        try {
          Response result = client.performRequest("GET", "_cluster/health");
          if (result.getStatusLine().getStatusCode() != 200) return false;
          Map<String, String> healthJson = mapper.readValue(
            result.getEntity().getContent(),
            new TypeReference<Map<String, String>>() {
            }
          );
          return "green".equals(healthJson.get("status"));
        } catch (IOException e) {
          return false;
        }
      }
    };
  }

  public interface ESInitalizer {
    void initialize(RestClient client);
  }

}
