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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;
import java.util.Observable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static tech.beshu.ror.commons.Constants.SETTINGS_YAML_FILE;

abstract public class SettingsObservable extends Observable {
  protected static final String SETTINGS_NOT_FOUND_MESSAGE = "no settings found in index";

  protected Map<String, ?> current;

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
    updateSettings(getFromFile());
  }

  protected abstract boolean isClusterReady();

  protected Map<String, ?> getFromFile() {
    LoggerShim logger = getLogger();

    String filePath = Constants.makeAbsolutePath("config" + File.separator);

    if (!filePath.endsWith(File.separator)) {
      filePath += File.separator;
    }
    filePath += SETTINGS_YAML_FILE;

    String finalFilePath = filePath;

    final Map<String, Object> settingsMap = new HashMap<>();
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      try {
        String slurped = new String(Files.readAllBytes(Paths.get(finalFilePath)));
        settingsMap.putAll(mkSettingsFromYAMLString(slurped));

        logger.info("Loaded good settings from " + finalFilePath);
      } catch (Throwable t) {
        logger.info(
          "Could not find settings in "
            + finalFilePath + ", falling back to elasticsearch.yml (" + t.getMessage() + ")");
        settingsMap.putAll(getFomES());
      }
      return null;
    });
    return settingsMap;
  }

  protected abstract Map<? extends String, ?> mkSettingsFromYAMLString(String slurped);

  protected abstract LoggerShim getLogger();

  protected abstract Map<String, ?> getFomES();

  protected abstract Map<String, ?> getFromIndex();

  public void updateFromIndex() {
    try {
      getLogger().debug("[CLUSTERWIDE SETTINGS] checking index..");
      updateSettings(getFromIndex());
      getLogger().info("[CLUSTERWIDE SETTINGS] good settings found in index, overriding local YAML file");
    } catch (Throwable t) {
      if (SETTINGS_NOT_FOUND_MESSAGE.equals(t.getMessage())) {
        getLogger().info("[CLUSTERWIDE SETTINGS] index settings not found. Will keep on using the local YAML file. " +
                           "Learn more about clusterwide settings at https://readonlyrest.com/pro.html ");
      }
      else {
        t.printStackTrace();
      }
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
            try {
              updateFromIndex();
            } catch (Exception e) {
              if (e.getMessage().equals(SETTINGS_NOT_FOUND_MESSAGE)) {
                logger.info("[CLUSTERWIDE SETTINGS] Settings not found in index .readonlyrest, defaulting to local node setting...");
              }
              else {
                e.printStackTrace();
              }
            }
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

    }
    else {
      // Never going to complete
      logger.info("Skipping settings index poller...");
    }
  }
}
