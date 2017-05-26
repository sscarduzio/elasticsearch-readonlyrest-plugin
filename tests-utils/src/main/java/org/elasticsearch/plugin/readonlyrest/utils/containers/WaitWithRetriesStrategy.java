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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.time.Instant;

import static org.elasticsearch.plugin.readonlyrest.utils.containers.ContainerUtils.checkTimeout;

public abstract class WaitWithRetriesStrategy extends GenericContainer.AbstractWaitStrategy {

  private static Logger logger = LogManager.getLogger(WaitWithRetriesStrategy.class);
  private static Duration WAIT_BETWEEN_RETRIES = Duration.ofSeconds(1);

  private final String containerName;

  public WaitWithRetriesStrategy(String containerName) {
    this.containerName = containerName;
  }

  @Override
  protected void waitUntilReady() {
    logger.info("Waiting for '" + containerName + "' container ...");
    final Instant startTime = Instant.now();
    while (!isReady() && !checkTimeout(startTime, startupTimeout)) {
      try {
        Thread.sleep(WAIT_BETWEEN_RETRIES.toMillis());
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    logger.info("'" + containerName + "' container stated");
    onContainerStarted();
  }

  protected void onContainerStarted() {
    // empty implementation
  }

  protected abstract boolean isReady();
}
