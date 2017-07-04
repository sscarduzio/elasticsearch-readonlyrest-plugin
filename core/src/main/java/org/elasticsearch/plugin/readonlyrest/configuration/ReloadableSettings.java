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
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RorSettings;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public abstract class ReloadableSettings {

  private final AtomicReference<RorSettings> rorSettings = new AtomicReference<>();
  private final SettingsManager settingsManager;
  private WeakHashMap<Consumer<RorSettings>, Boolean> onSettingsUpdateListeners = new WeakHashMap<>();

  public ReloadableSettings(SettingsManager settingsManager) throws IOException {

    this.rorSettings.set(RorSettings.from(new RawSettings(settingsManager.getCurrentSettings())));
    this.settingsManager = settingsManager;
  }

  public RorSettings getRorSettings() {
    return rorSettings.get();
  }

  public void addListener(Consumer<RorSettings> onSettingsUpdate) {
    this.onSettingsUpdateListeners.put(onSettingsUpdate, true);
    onSettingsUpdate.accept(rorSettings.get());
  }

  public CompletableFuture<Optional<Throwable>> reload() {
    return CompletableFuture
      .supplyAsync(() -> {
                     Map<String, ?> fromIndex = settingsManager.reloadSettingsFromIndex();
                     RawSettings raw = new RawSettings(fromIndex);
                     RorSettings ror = RorSettings.from(raw);
                     this.rorSettings.set(ror);
                     return Optional.<Throwable>empty();
                   }
        , MoreExecutors.directExecutor()).exceptionally(th ->
                                                          Optional.of(th)
      );
  }

  private void notifyListeners() {
    onSettingsUpdateListeners.keySet()
      .forEach(listener -> listener.accept(rorSettings.get()));
  }
}
