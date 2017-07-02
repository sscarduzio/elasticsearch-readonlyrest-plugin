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
package org.elasticsearch.plugin.readonlyrest.configuration;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.plugin.readonlyrest.settings.BlockSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RorSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ExternalAuthenticationServiceSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ProxyAuthDefinitionSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.EnabledSslSettings;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.SslSettings;

import java.util.Map;

public abstract class AllowedSettings {

  public static Map<String, SettingType> list() {
    String prefix = RorSettings.ATTRIBUTE_NAME + ".";
    String sslPrefix = prefix + SslSettings.ATTRIBUTE_NAME + ".";
    String rule_prefix = prefix + BlockSettings.ATTRIBUTE_NAME + ".";
    String users_prefix = prefix + UserSettingsCollection.ATTRIBUTE_NAME + ".";
    String ldaps_prefix = prefix + LdapSettingsCollection.ATTRIBUTE_NAME + ".";
    String proxy_auth_configs_prefix = prefix + ProxyAuthDefinitionSettingsCollection.ATTRIBUTE_NAME + ".";
    String user_groups_providers_prefix = prefix + UserGroupsProviderSettingsCollection.ATTRIBUTE_NAME + ".";
    String external_authentication_service_configs_prefix =
      prefix + ExternalAuthenticationServiceSettingsCollection.ATTRIBUTE_NAME + ".";

    return ImmutableMap.<String, SettingType>builder()

      // Top level
      .put(prefix + RorSettings.ATTRIBUTE_ENABLE, SettingType.BOOL)
      .put(prefix + RorSettings.ATTRIBUTE_FORBIDDEN_RESPONSE, SettingType.STRING)
      .put(prefix + RorSettings.ATTRIBUTE_SEARCHLOG, SettingType.BOOL)
      .put(prefix + RorSettings.PROMPT_FOR_BASIC_AUTH, SettingType.BOOL)
      .put(prefix + RorSettings.AUDIT_COLLECTOR, SettingType.BOOL)

      // SSL
      .put(sslPrefix + SslSettings.ATTRIBUTE_ENABLE, SettingType.BOOL)
      .put(sslPrefix + EnabledSslSettings.ATTRIBUTE_KEYSTORE_FILE, SettingType.STRING)
      .put(sslPrefix + EnabledSslSettings.ATTRIBUTE_KEYSTORE_PASS, SettingType.STRING)
      .put(sslPrefix + EnabledSslSettings.ATTRIBUTE_KEY_PASS, SettingType.STRING)
      .put(sslPrefix + EnabledSslSettings.ATTRIBUTE_KEY_ALIAS, SettingType.STRING)
      .put(sslPrefix + EnabledSslSettings.ATTRIBUTE_PRIVKEY_PEM, SettingType.STRING)
      .put(sslPrefix + EnabledSslSettings.ATTRIBUTE_CERTCHAIN_PEM, SettingType.STRING)

      // Groups
      .put(rule_prefix, SettingType.GROUP)
      .put(users_prefix, SettingType.GROUP)
      .put(user_groups_providers_prefix, SettingType.GROUP)
      .put(external_authentication_service_configs_prefix, SettingType.GROUP)
      .put(ldaps_prefix, SettingType.GROUP)
      .put(proxy_auth_configs_prefix, SettingType.GROUP)

      .build();
  }

  public enum SettingType {
    BOOL, STRING, GROUP;
  }
}
