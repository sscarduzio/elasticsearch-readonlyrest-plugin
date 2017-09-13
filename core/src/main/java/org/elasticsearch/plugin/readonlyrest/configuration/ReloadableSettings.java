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
package org.elasticsearch.plugin.readonlyrest.configuration;

import com.google.common.util.concurrent.MoreExecutors;
import org.elasticsearch.plugin.readonlyrest.ESVersion;
import org.elasticsearch.plugin.readonlyrest.LoggerShim;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RorSettings;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public abstract class ReloadableSettings {

  public static final String SETTINGS_NOT_FOUND_MESSAGE = "no settings found in index";
  private static final String SETTINGS_FILENAME = "readonlyrest.yml";
  private final AtomicReference<RorSettings> rorSettings = new AtomicReference<>();
  private final SettingsManager settingsManager;
  private final LoggerShim logger;
  private CompletableFuture<Void> clientReadyFuture;
  private WeakHashMap<Consumer<RorSettings>, Boolean> onSettingsUpdateListeners = new WeakHashMap<>();

  public ReloadableSettings(SettingsManager settingsManager) throws IOException {

    this.rorSettings.set(RorSettings.from(new RawSettings(settingsManager.getCurrentSettings(SETTINGS_FILENAME))));

    this.settingsManager = settingsManager;
    this.logger = settingsManager.getContext().logger(getClass());

    // This stuff doesn't matter for 2.x as it does it differently.
    if (settingsManager.getContext().getVersion().after(ESVersion.V_5_0_0)) {

      // When ReloadableSettings is created at boot time, wait the cluster to stabilise and read in-index settings.
      CompletableFuture<Void> result = new CompletableFuture<>();
      ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

      if (System.getProperty("com.readonlyrest.reloadsettingsonboot") == null) {
        ScheduledFuture scheduledJob = executor
          .scheduleWithFixedDelay(() -> {
            if (settingsManager.isClusterReady()) {
              logger.info("[CLUSTERWIDE SETTINGS] Cluster is ready!");
              result.complete(null);
              reload();
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

  public RorSettings getRorSettings() {
    return rorSettings.get();
  }

  public void addListener(Consumer<RorSettings> onSettingsUpdate) {
    this.onSettingsUpdateListeners.put(onSettingsUpdate, true);
    onSettingsUpdate.accept(rorSettings.get());
  }


  public CompletableFuture<Optional<Throwable>> reload() {
    return clientReadyFuture
      .thenCompose((x) ->
                     CompletableFuture.supplyAsync(() -> {
                                                     logger.debug("[CLUSTERWIDE SETTINGS] checking index..");
                                                     Map<String, ?> fromIndex = settingsManager.reloadSettingsFromIndex();
                                                     RawSettings raw = new RawSettings(fromIndex);
                                                     RorSettings ror = RorSettings.from(raw);
                                                     this.rorSettings.set(ror);
                                                     logger.info("[CLUSTERWIDE SETTINGS] good settings found in index, overriding elasticsearch.yml");
                                                     notifyListeners();
                                                     return Optional.<Throwable>empty();
                                                   }
                       , MoreExecutors.directExecutor())
                       .exceptionally(th -> {
                         if (th instanceof SettingsMalformedException) {
                           logger.error("[CLUSTERWIDE SETTINGS] configuration error: " + th.getCause().getMessage());
                         }
                         else if (th.getMessage().contains(SETTINGS_NOT_FOUND_MESSAGE)) {
                           logger.info("[CLUSTERWIDE SETTINGS] index settings not found. Will keep on using the local YAML file. " +
                                         "Learn more about clusterwide settings at https://readonlyrest.com/pro.html ");
                         }
                         return Optional.of(th);
                       })
      );
  }

  private void notifyListeners() {
    onSettingsUpdateListeners.keySet()
      .forEach(listener -> listener.accept(rorSettings.get()));
  }
}
