package org.elasticsearch.plugin.readonlyrest.settings;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;

public class ESSettings extends Settings {

  private RorSettings rorSettings;

  @SuppressWarnings("unchecked")
  public static ESSettings loadFrom(File file) throws IOException {
    Yaml yaml = new Yaml();
    try (FileInputStream stream = new FileInputStream(file)) {
      Map<String, ?> parsedData = (Map<String, ?>) yaml.load(stream);
      return new ESSettings(new RawSettings(parsedData));
    }
  }

  private ESSettings(RawSettings settings) {
    this.rorSettings = RorSettings.from(settings);
  }

  public RorSettings getRorSettings() {
    return rorSettings;
  }

}
