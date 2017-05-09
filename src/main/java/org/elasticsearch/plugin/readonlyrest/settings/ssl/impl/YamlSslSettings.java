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
