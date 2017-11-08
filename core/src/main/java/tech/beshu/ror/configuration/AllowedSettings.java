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
package tech.beshu.ror.configuration;

import com.google.common.collect.ImmutableMap;
import tech.beshu.ror.commons.settings.BasicSettings;
import tech.beshu.ror.settings.BlockSettings;
import tech.beshu.ror.settings.RorSettings;
import tech.beshu.ror.settings.definitions.ExternalAuthenticationServiceSettingsCollection;
import tech.beshu.ror.settings.definitions.LdapSettingsCollection;
import tech.beshu.ror.settings.definitions.ProxyAuthDefinitionSettingsCollection;
import tech.beshu.ror.settings.definitions.UserGroupsProviderSettingsCollection;
import tech.beshu.ror.settings.definitions.UserSettingsCollection;

import java.util.Map;

public abstract class AllowedSettings {

  public static Map<String, SettingType> list() {
    String prefix = RorSettings.ATTRIBUTE_NAME + ".";
    String sslPrefix = prefix + "ssl" + ".";
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
      .put(prefix + BasicSettings.CUSTOM_AUDIT_SERIALIZER, SettingType.STRING)

      // SSL
      .put(sslPrefix + "enable", SettingType.BOOL)
      .put(sslPrefix + BasicSettings.ATTRIBUTE_SSL_KEYSTORE_FILE, SettingType.STRING)
      .put(sslPrefix + BasicSettings.ATTRIBUTE_SSL_KEYSTORE_PASS, SettingType.STRING)
      .put(sslPrefix + BasicSettings.ATTRIBUTE_SSL_KEY_PASS, SettingType.STRING)
      .put(sslPrefix + BasicSettings.ATTRIBUTE_SSL_KEY_ALIAS, SettingType.STRING)

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
