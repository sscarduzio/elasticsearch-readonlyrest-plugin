package org.elasticsearch.plugin.readonlyrest.settings;

public class ESSettings {

  private RorSettings rorSettings;

  public ESSettings(RawSettings settings) {
    this.rorSettings = RorSettings.from(settings);
  }

  public RorSettings getRorSettings() {
    return rorSettings;
  }

}
