package org.elasticsearch.plugin.readonlyrest.settings.ssl.impl;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.SslSettings;

import static org.elasticsearch.plugin.readonlyrest.settings.ssl.SslSettings.ATTRIBUTE_ENABLE;

public class YamlSslSettings {

  static SslSettings from(RawSettings settings) {
    return settings.booleanReq(ATTRIBUTE_ENABLE)
        ? YamlEnabledSslSettings.from(settings)
        : YamlDisabledSslSettings.INSTANCE;
  }
}
