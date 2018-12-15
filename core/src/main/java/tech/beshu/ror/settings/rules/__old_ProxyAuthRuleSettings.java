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
package tech.beshu.ror.settings.rules;

import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.settings.AuthKeyProviderSettings;
import tech.beshu.ror.settings.RuleSettings;
import tech.beshu.ror.settings.definitions.ProxyAuthDefinitionSettingsCollection;

import java.util.List;

public class __old_ProxyAuthRuleSettings implements RuleSettings, AuthKeyProviderSettings {

  public static final String ATTRIBUTE_NAME = "proxy_auth";

  private static final String PROXY_AUTH_CONFIG = "proxy_auth_config";
  private static final String USERS = "users";

  private static final String DEFAULT_HEADER_NAME = "X-Forwarded-User";

  private final List<String> users;
  private final String userIdHeader;

  private __old_ProxyAuthRuleSettings(List<String> users, String userIdHeader) {
    this.users = users;
    this.userIdHeader = userIdHeader;
  }

  @SuppressWarnings("unchecked")
  public static __old_ProxyAuthRuleSettings from(RawSettings settings,
                                           ProxyAuthDefinitionSettingsCollection proxyAuthDefinitionSettingsCollection) {
    String providerName = settings.stringReq(PROXY_AUTH_CONFIG);
    return new __old_ProxyAuthRuleSettings(
      (List<String>) settings.notEmptyListReq(USERS),
      proxyAuthDefinitionSettingsCollection.get(providerName).getUserIdHeader()
    );
  }

  public static __old_ProxyAuthRuleSettings from(List<String> users) {
    return new __old_ProxyAuthRuleSettings(users, DEFAULT_HEADER_NAME);
  }

  public List<String> getUsers() {
    return users;
  }

  public String getUserIdHeader() {
    return userIdHeader;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
