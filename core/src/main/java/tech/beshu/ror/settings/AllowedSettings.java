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
package tech.beshu.ror.settings;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

// todo: refactor
// todo: add force_load?
public class AllowedSettings {

  public static final String ATTRIBUTE_NAME = "readonlyrest";
  public static final String ATTRIBUTE_ENABLE = "enable";
  public static final String ATTRIBUTE_FORBIDDEN_RESPONSE = "response_if_req_forbidden";
  public static final String ATTRIBUTE_SEARCHLOG = "searchlog";
  public static final String PROMPT_FOR_BASIC_AUTH = "prompt_for_basic_auth";
  public static final String AUDIT_COLLECTOR = "audit_collector";

  // SSL
  public static final String ATTRIBUTE_SSL_KEYSTORE_FILE = "keystore_file";
  public static final String ATTRIBUTE_SSL_KEYSTORE_PASS = "keystore_pass";
  public static final String ATTRIBUTE_SSL_KEY_PASS = "key_pass";
  public static final String ATTRIBUTE_SSL_KEY_ALIAS = "key_alias";

  public static final String CUSTOM_AUDIT_SERIALIZER = "audit_serializer";
  public static final String CACHE_HASHING_ALGO = "cache_hashing_algo";

  public static Map<String, SettingType> list() {
    String prefix = ATTRIBUTE_NAME + ".";
    String sslPrefix = prefix + "ssl" + ".";
    String rule_prefix = prefix + "access_control_rules.";
    String users_prefix = prefix + "users.";
    String ldaps_prefix = prefix + "ldaps.";
    String proxy_auth_configs_prefix = prefix + "proxy_auth_configs.";
    String user_groups_providers_prefix = prefix + "user_groups_providers.";
    String external_authentication_service_configs_prefix = "external_authentication_service_configs.";

    return ImmutableMap.<String, SettingType>builder()

      // Top level
      .put(prefix + ATTRIBUTE_ENABLE, SettingType.BOOL)
      .put(prefix + ATTRIBUTE_FORBIDDEN_RESPONSE, SettingType.STRING)
      .put(prefix + ATTRIBUTE_SEARCHLOG, SettingType.BOOL)
      .put(prefix + PROMPT_FOR_BASIC_AUTH, SettingType.BOOL)
      .put(prefix + AUDIT_COLLECTOR, SettingType.BOOL)
      .put(prefix + CUSTOM_AUDIT_SERIALIZER, SettingType.STRING)
      .put(prefix + CACHE_HASHING_ALGO, SettingType.STRING)

      // SSL
      .put(sslPrefix + "enable", SettingType.BOOL)
      .put(sslPrefix + ATTRIBUTE_SSL_KEYSTORE_FILE, SettingType.STRING)
      .put(sslPrefix + ATTRIBUTE_SSL_KEYSTORE_PASS, SettingType.STRING)
      .put(sslPrefix + ATTRIBUTE_SSL_KEY_PASS, SettingType.STRING)
      .put(sslPrefix + ATTRIBUTE_SSL_KEY_ALIAS, SettingType.STRING)

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
