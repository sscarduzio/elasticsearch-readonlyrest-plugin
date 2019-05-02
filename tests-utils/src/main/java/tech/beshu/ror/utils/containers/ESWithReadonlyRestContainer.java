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

import com.google.common.base.Strings;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.shaded.com.fasterxml.jackson.core.Version;
import org.testcontainers.shaded.com.fasterxml.jackson.core.type.TypeReference;
import org.testcontainers.shaded.com.fasterxml.jackson.core.util.VersionUtil;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;
import tech.beshu.ror.utils.misc.Tuple;
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static tech.beshu.ror.utils.containers.ContainerUtils.checkTimeout;

public class ESWithReadonlyRestContainer extends GenericContainer<ESWithReadonlyRestContainer> {

  private static Logger logger = LogManager.getLogger(ESWithReadonlyRestContainer.class);
  private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

  private static int ES_PORT = 9200;
  private static int ES_TRANSPORT_PORT = 9300;
  private static Duration WAIT_BETWEEN_RETRIES = Duration.ofSeconds(1);
  private static Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofSeconds(2400);
  private static String ADMIN_LOGIN = "admin";
  private static String ADMIN_PASSWORD = "container";

  private static String esVersion;

  private ESWithReadonlyRestContainer(String esVersion, ImageFromDockerfile imageFromDockerfile) {
    super(imageFromDockerfile);
    ESWithReadonlyRestContainer.esVersion = esVersion;
  }

  public static ESWithReadonlyRestContainer create(RorPluginGradleProject project,
      String elasticsearchConfig,
      Optional<ESWithReadonlyRestContainer.ESInitalizer> initalizer) {
    return create(project, ContainerUtils.getResourceFile(elasticsearchConfig), initalizer);
  }

  private static boolean greaterOrEqualThan(String esVersion, int maj, int min, int patchLevel) {
    if (Strings.isNullOrEmpty(esVersion)) {
      throw new IllegalArgumentException("invalid esVersion: " + esVersion);
    }
    return VersionUtil
        .parseVersion(esVersion, "x", "y")
        .compareTo(
            new Version(maj, min, patchLevel, "", "x", "y")) >= 0;
  }

  public static void main(String[] args) {

  }

  public static ESWithReadonlyRestContainer create(RorPluginGradleProject project,
      File elasticsearchConfigFile,
      Optional<ESWithReadonlyRestContainer.ESInitalizer> initalizer) {
    File pluginFile = project.assemble().orElseThrow(() ->
        new ContainerCreationException("Plugin file assembly failed")
    );

    String dockerImage = greaterOrEqualThan(project.getESVersion(), 6, 3, 0) ?
        "docker.elastic.co/elasticsearch/elasticsearch-oss" :
        "docker.elastic.co/elasticsearch/elasticsearch";
    String elasticsearchConfigName = "elasticsearch.yml";
    String log4j2FileName = "log4j2.properties";
    String javaOptionsFileName = "jvm.options";
    String keystoreFileName = "keystore.jks";

    logger.info(sdf.format(System.currentTimeMillis()) + " Creating ES container ...");

    ESWithReadonlyRestContainer container = new ESWithReadonlyRestContainer(
        project.getESVersion(),
        new ImageFromDockerfile()
            .withFileFromFile(pluginFile.getAbsolutePath(), pluginFile)
            .withFileFromFile(elasticsearchConfigName, elasticsearchConfigFile)
            .withFileFromFile(log4j2FileName, ContainerUtils.getResourceFile("/" + log4j2FileName))
            .withFileFromFile(keystoreFileName, ContainerUtils.getResourceFile("/" + keystoreFileName))
            .withFileFromFile(javaOptionsFileName, ContainerUtils.getResourceFile("/" + javaOptionsFileName))
            .withDockerfileFromBuilder(builder -> {
              builder
                  .from(dockerImage + ":" + project.getESVersion())
                  .env("TEST_VAR", "dev")
                  .copy(pluginFile.getAbsolutePath(), "/tmp/")
                  .copy(log4j2FileName, "/usr/share/elasticsearch/config/")
                  .copy(keystoreFileName, "/usr/share/elasticsearch/config/")
                  .copy(javaOptionsFileName, "/usr/share/elasticsearch/config/")
                  .copy(elasticsearchConfigName, "/usr/share/elasticsearch/config/readonlyrest.yml")
                  .run("/usr/share/elasticsearch/bin/elasticsearch-plugin remove x-pack --purge || rm -rf /usr/share/elasticsearch/plugins/*")

                  .run("grep -v xpack /usr/share/elasticsearch/config/elasticsearch.yml > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
                  .run("echo 'http.type: ssl_netty4' >> /usr/share/elasticsearch/config/elasticsearch.yml")
                  .run("sed -i \"s|debug|info|g\" /usr/share/elasticsearch/config/log4j2.properties")

                  .user("root")
                  .run("chown elasticsearch:elasticsearch config/*");

              if(greaterOrEqualThan(project.getESVersion(), 7, 0, 0)){
                builder
                    .run("egrep -v 'node\\.name|initial_master_nodes' /usr/share/elasticsearch/config/elasticsearch.yml > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
                    .run("echo 'node.name: n1' >> /usr/share/elasticsearch/config/elasticsearch.yml")
                    .run("echo 'cluster.initial_master_nodes: n1' >> /usr/share/elasticsearch/config/elasticsearch.yml");
              }

              builder.user("elasticsearch")
                     .env("ES_JAVA_OPTS", "-Xms512m -Xmx512m -Djava.security.egd=file:/dev/./urandoms -Dcom.unboundid.ldap.sdk.debug.enabled=true")
                     .run("yes | /usr/share/elasticsearch/bin/elasticsearch-plugin install file:///tmp/" + pluginFile.getName());
              logger.info("Dockerfile\n" + builder.build());
            })

    );
    return container
        .withLogConsumer((l) -> {
          String logLine = new StringBuilder(3)
              .append(sdf.format(System.currentTimeMillis()))
              .append(" ")
              .append(l.getUtf8String())
              .toString();
          System.out.print(logLine);
        })
        .withExposedPorts(ES_PORT, ES_TRANSPORT_PORT)
        .waitingFor(container.waitStrategy(initalizer).withStartupTimeout(CONTAINER_STARTUP_TIMEOUT));

  }

  public String getEsVersion() {
    return esVersion;
  }

  public String getESHost() {
    return this.getContainerIpAddress();
  }

  public Integer getESPort() {
    return this.getMappedPort(ES_PORT);
  }

  public Integer getTransportPort() {
    return this.getMappedPort(ES_TRANSPORT_PORT);
  }

  public RestClient getClient(Header... headers) {
    return new RestClient(true, getESHost(), getESPort(), Optional.empty(), headers);
  }

  public RestClient getBasicAuthClient(String name, String password) {
    return new RestClient(true, getESHost(), getESPort(), Optional.of(Tuple.from(name, password)));
  }

  public RestClient getAdminClient() {
    return new RestClient(true, getESHost(), getESPort(), Optional.of(Tuple.from(ADMIN_LOGIN, ADMIN_PASSWORD)));
  }

  private WaitStrategy waitStrategy(Optional<ESWithReadonlyRestContainer.ESInitalizer> initalizer) {
    final ObjectMapper mapper = new ObjectMapper();
    return new org.testcontainers.containers.wait.strategy.AbstractWaitStrategy() {
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
        initalizer.ifPresent(i -> i.initialize(client));
        logger.info("ES container started and listening " + getESHost() + ":" + getESPort());
      }

      private boolean isReady(RestClient client) {
        try {
          HttpResponse result = client.execute(new HttpGet(client.from("_cluster/health")));
          if (result.getStatusLine().getStatusCode() != 200) {
            return false;
          }
          Map<String, String> healthJson = mapper.readValue(
              result.getEntity().getContent(),
              new TypeReference<Map<String, String>>() {
              }
          );
          return "green".equals(healthJson.get("status"));
        } catch (Exception e) {
          return false;
        }
      }
    };
  }

  public interface ESInitalizer {
    void initialize(RestClient adminClient);
  }
}
