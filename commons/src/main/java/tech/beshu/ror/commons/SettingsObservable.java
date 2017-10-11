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

package tech.beshu.ror.commons;

import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.ESVersion;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.util.Map;
import java.util.Observable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

abstract public class SettingsObservable extends Observable {

  protected Map<String, ?> current;
  private CompletableFuture<Void> clientReadyFuture;

  void updateSettings(Map<String, ?> newSettings) {
    this.current = newSettings;
    setChanged();
    notifyObservers();
  }

  public Map<String, ?> getCurrent() {
    return current;
  }

  public void updateFromLocalNode() {
    // Includes fallback to ES
    updateSettings(getFromFile()
    );
  }

  protected abstract boolean isClusterReady();

  protected abstract Map<String, ?> getFromFile();

  protected abstract Map<String, ?> getFomES();

  protected abstract Map<String, ?> getFromIndex();

  public void updateFromIndex() {
    try {
      updateSettings(getFromIndex());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void pollForIndex(ESContext context) {
    if (context.getVersion().before(ESVersion.V_5_0_0)) {
      return;
    }
    LoggerShim logger = context.logger(getClass());

    // When ReloadableSettings is created at boot time, wait the cluster to stabilise and read in-index settings.
    CompletableFuture<Void> result = new CompletableFuture<>();
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

    if (System.getProperty("com.readonlyrest.reloadsettingsonboot") == null) {
      ScheduledFuture scheduledJob = executor
        .scheduleWithFixedDelay(() -> {
          if (isClusterReady()) {
            logger.info("[CLUSTERWIDE SETTINGS] Cluster is ready!");
            result.complete(null);
            updateFromIndex();
          }
          else {
            logger.info("[CLUSTERWIDE SETTINGS] Cluster not ready...");
          }
        }, 1, 1, TimeUnit.SECONDS);

      // When the cluster is up, stop polling.
      result.thenAccept((_x) -> {
        logger.info("[CLUSTERWIDE SETTINGS] Stopping cluster poller..");
        scheduledJob.cancel(false);
        executor.shutdown();
      });

      this.clientReadyFuture = result;
    }
    else {
      // Never going to complete
      logger.info("Skipping settings index poller...");
      this.clientReadyFuture = new CompletableFuture<>();
    }
  }
}
