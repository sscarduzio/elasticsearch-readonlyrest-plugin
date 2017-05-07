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
