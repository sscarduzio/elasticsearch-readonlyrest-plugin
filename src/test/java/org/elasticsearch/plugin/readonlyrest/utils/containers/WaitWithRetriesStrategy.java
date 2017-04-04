package org.elasticsearch.plugin.readonlyrest.utils.containers;

import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

import static org.elasticsearch.plugin.readonlyrest.utils.containers.ContainerUtils.checkTimeout;

public abstract class WaitWithRetriesStrategy extends GenericContainer.AbstractWaitStrategy {

  private static Logger logger = Logger.getLogger(WaitWithRetriesStrategy.class.getName());

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

  protected void onContainerStarted() {}

  protected abstract boolean isReady();
}
