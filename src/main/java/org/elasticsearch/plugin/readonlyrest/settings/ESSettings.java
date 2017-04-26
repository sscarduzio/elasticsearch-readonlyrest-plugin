package org.elasticsearch.plugin.readonlyrest.settings;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;

public class ESSettings extends Settings {

  private RorSettings rorSettings;

  public static ESSettings loadFrom(File file) throws IOException {
    Yaml yaml = new Yaml();
    try (FileInputStream stream = new FileInputStream(file)) {
      LinkedHashMap<?, ?> parsedData = (LinkedHashMap<?, ?>) yaml.load(stream);
      return new ESSettings(parsedData);
    }
  }

  private ESSettings(LinkedHashMap<?, ?> data) {
    this.rorSettings = RorSettings.from(data);
  }

  public RorSettings getRorSettings() {
    return rorSettings;
  }

}
