package org.elasticsearch.plugin.readonlyrest.settings;

import org.elasticsearch.plugin.readonlyrest.settings.ssl.DisabledSSLSettings;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.EnabledSSLSettings;

public interface SSLSettings {
  static SSLSettings from(RawSettings settings) {
    return settings.booleanReq("enable")
        ? EnabledSSLSettings.from(settings)
        : DisabledSSLSettings.INSTANCE;
  }
}
