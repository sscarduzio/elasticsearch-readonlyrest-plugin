package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

/**
 * Created by sscarduzio on 25/06/2017.
 */
public class TestUtils {
  @SuppressWarnings("unchecked")
  public static RawSettings fromYAMLString(String yamlContent) {
    Yaml yaml = new Yaml();
    Map<String, ?> parsedData = (Map<String, ?>) yaml.load(yamlContent);

    return new RawSettings(parsedData);
  }
}
