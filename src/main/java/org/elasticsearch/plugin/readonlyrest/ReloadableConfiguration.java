package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.plugin.readonlyrest.settings.ESSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RorSettings;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ReloadableConfiguration {

  private final AtomicReference<RorSettings> rorSettings;
  private final Consumer<RorSettings> onSettingsUpdate;

  public ReloadableConfiguration(Consumer<RorSettings> onSettingsUpdate) throws IOException {
    this.rorSettings = new AtomicReference<>(
        ESSettings.loadFrom(new File("/config/elasticsearch.yml")).getRorSettings()
    );
    this.onSettingsUpdate = onSettingsUpdate;
    this.onSettingsUpdate.accept(rorSettings.get());
  }
}
