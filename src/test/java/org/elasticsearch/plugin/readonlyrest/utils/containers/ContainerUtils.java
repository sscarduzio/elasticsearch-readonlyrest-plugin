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

import org.elasticsearch.plugin.readonlyrest.utils.containers.exceptions.ContainerCreationException;
import org.elasticsearch.plugin.readonlyrest.utils.containers.exceptions.ContainerStartupTimeoutException;
import org.testcontainers.containers.GenericContainer;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class ContainerUtils {
  private ContainerUtils() {
  }

  public static File getResourceFile(String path) {
    try {
      return Paths.get(ContainerUtils.class.getResource(path).toURI()).toFile();
    } catch (URISyntaxException e) {
      throw new ContainerCreationException("Cannot find resource file", e);
    }
  }

  public static boolean checkTimeout(Instant startTime, Duration startupTimeout) {
    if (startupTimeout.minus(Duration.between(startTime, Instant.now())).isNegative()) {
      throw new ContainerStartupTimeoutException("Container was not started within " + startupTimeout.toString());
    }
    return false;
  }

  public static Optional<String> getIpAddress(GenericContainer<?> container) {
    return Optional.ofNullable(container.getContainerInfo())
        .map(info -> info
            .getNetworkSettings()
            .getNetworks()
            .get("bridge")
            .getIpAddress());
  }
}
