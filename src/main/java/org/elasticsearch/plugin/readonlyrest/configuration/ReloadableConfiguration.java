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

import org.elasticsearch.plugin.readonlyrest.settings.ESSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RorSettings;

import java.io.File;
import java.io.IOException;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public abstract class ReloadableConfiguration {

  private final AtomicReference<RorSettings> rorSettings = new AtomicReference<>();
  private WeakHashMap<Consumer<RorSettings>, Boolean> onSettingsUpdateListeners = new WeakHashMap<>();

  public ReloadableConfiguration(File yaml) throws IOException {
    this.rorSettings.set(
        new ESSettings(RawSettings.fromFile(yaml)).getRorSettings()
    );
  }

  public void addListener(Consumer<RorSettings> onSettingsUpdate) {
    this.onSettingsUpdateListeners.put(onSettingsUpdate, true);
    onSettingsUpdate.accept(rorSettings.get());
  }

  public void reload(ConfigurationContentProvider provider) {
    provider.getConfiguration().thenAccept(configurationContent -> {
          this.rorSettings.set(new ESSettings(RawSettings.fromString(configurationContent)).getRorSettings());
          notifyListeners();
        }
    );
  }

  private void notifyListeners() {
    onSettingsUpdateListeners.keySet()
        .forEach(listener -> listener.accept(rorSettings.get()));
  }
}
