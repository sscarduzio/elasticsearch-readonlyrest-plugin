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
package org.elasticsearch.plugin.readonlyrest.es;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.settings.BlockSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RorSettings;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.SslSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ExternalAuthenticationServiceSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ProxyAuthDefinitionSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.EnabledSslSettings;

import java.util.Arrays;
import java.util.List;

public enum AllowedSettings {
  INSTANCE;

  public List<Setting<?>> list() {
    String prefix = RorSettings.ATTRIBUTE_NAME +".";
    String sslPrefix = prefix + SslSettings.ATTRIBUTE_NAME + ".";
    String rule_prefix = prefix + BlockSettings.ATTRIBUTE_NAME + ".";
    String users_prefix = prefix + UserSettingsCollection.ATTRIBUTE_NAME + ".";
    String ldaps_prefix = prefix + LdapSettingsCollection.ATTRIBUTE_NAME + ".";
    String proxy_auth_configs_prefix = prefix + ProxyAuthDefinitionSettingsCollection.ATTRIBUTE_NAME + ".";
    String user_groups_providers_prefix = prefix + UserGroupsProviderSettingsCollection.ATTRIBUTE_NAME + ".";
    String external_authentication_service_configs_prefix =
        prefix + ExternalAuthenticationServiceSettingsCollection.ATTRIBUTE_NAME + ".";

    return Arrays.asList(
        bool(prefix + RorSettings.ATTRIBUTE_ENABLE),
        str(prefix + RorSettings.ATTRIBUTE_FORBIDDEN_RESPONSE),
        bool(prefix + RorSettings.ATTRIBUTE_SEARCHLOG),
        bool(prefix + RorSettings.PROMPT_FOR_BASIC_AUTH),

        // SSL
        bool(sslPrefix + SslSettings.ATTRIBUTE_ENABLE),
        str(sslPrefix + EnabledSslSettings.ATTRIBUTE_KEYSTORE_FILE),
        str(sslPrefix + EnabledSslSettings.ATTRIBUTE_KEYSTORE_PASS),
        str(sslPrefix + EnabledSslSettings.ATTRIBUTE_KEY_PASS),
        str(sslPrefix + EnabledSslSettings.ATTRIBUTE_KEY_ALIAS),
        str(sslPrefix + EnabledSslSettings.ATTRIBUTE_PRIVKEY_PEM),
        str(sslPrefix + EnabledSslSettings.ATTRIBUTE_CERTCHAIN_PEM),

        grp(rule_prefix),
        grp(users_prefix),
        grp(ldaps_prefix),
        grp(proxy_auth_configs_prefix),
        grp(user_groups_providers_prefix),
        grp(external_authentication_service_configs_prefix)
    );
  }

  private static Setting<String> str(String name) {
    return new Setting<>(name, "", (value) -> value, Setting.Property.NodeScope);
  }

  private static Setting<Boolean> bool(String name) {
    return Setting.boolSetting(name, Boolean.FALSE, Setting.Property.NodeScope);
  }

  private static Setting<Settings> grp(String name) {
    return Setting.groupSetting(name, Setting.Property.Dynamic, Setting.Property.NodeScope);
  }
}
