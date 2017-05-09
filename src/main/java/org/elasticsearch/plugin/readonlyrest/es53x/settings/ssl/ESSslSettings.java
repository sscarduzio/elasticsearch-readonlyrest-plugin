/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
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
