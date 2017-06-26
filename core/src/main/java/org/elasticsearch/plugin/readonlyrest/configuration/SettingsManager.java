package org.elasticsearch.plugin.readonlyrest.configuration;

import java.util.Map;

/**
 * Created by sscarduzio on 25/06/2017.
 */
public interface SettingsManager {
  public Map<String, ?> getCurrentSettings();


  public Map<String, ?> reloadSettingsFromIndex();

  }
