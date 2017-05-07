package org.elasticsearch.plugin.readonlyrest.es53x.settings.ssl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.settings.RorSettings;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.SslSettings;

public class ESSslSettings {

  public static SslSettings from(Settings settings) {
    Settings sslSettings = settings.getByPrefix(RorSettings.ATTRIBUTE_NAME + "." + SslSettings.ATTRIBUTE_NAME + ".");

    boolean sslEnabled = sslSettings.getAsBoolean(SslSettings.ATTRIBUTE_ENABLE, sslSettings.size() > 1);
    if(!sslEnabled) return ESDisabledSslSettings.INSTANCE;

    return new ESEnabledSslSettings(sslSettings);
  }
}
