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
package org.elasticsearch.plugin.readonlyrest.settings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ExternalAuthenticationServiceSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ProxyAuthConfigSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserSettingsCollection;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RorSettings {

  public static final String ATTRIBUTE_NAME = "readonlyrest";
  public static final String ATTRIBUTE_ENABLE = "enable";
  public static final String ATTRIBUTE_FORBIDDEN_RESPONSE = "response_if_req_forbidden";
  public static final String ATTRIBUTE_SEARCHLOG = "searchlog";

  private static final String DEFAULT_FORBIDDEN_MESSAGE = "";
  private static final List<BlockSettings> DEFAULT_BLOCK_SETTINGS = Lists.newArrayList();

  private final boolean enable;
  private final String forbiddenMessage;
  private final List<BlockSettings> blocksSettings;

  static RorSettings from(RawSettings settings) {
    return new RorSettings(settings.inner(ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private RorSettings(RawSettings raw) {
    LdapSettingsCollection ldapSettingsCollection = LdapSettingsCollection.from(raw);
    UserGroupsProviderSettingsCollection userGroupsProviderSettingsCollection = UserGroupsProviderSettingsCollection.from(raw);
    ProxyAuthConfigSettingsCollection proxyAuthConfigSettingsCollection = ProxyAuthConfigSettingsCollection.from(raw);
    ExternalAuthenticationServiceSettingsCollection externalAuthenticationServiceSettingsCollection =
        ExternalAuthenticationServiceSettingsCollection.from(raw);
    AuthMethodCreatorsRegistry authMethodCreatorsRegistry = new AuthMethodCreatorsRegistry(proxyAuthConfigSettingsCollection);

    this.forbiddenMessage = raw.stringOpt(ATTRIBUTE_FORBIDDEN_RESPONSE).orElse(DEFAULT_FORBIDDEN_MESSAGE);
    this.blocksSettings = raw.notEmptyListOpt(BlockSettings.ATTRIBUTE_NAME).orElse(DEFAULT_BLOCK_SETTINGS).stream()
        .map(block -> BlockSettings.from(
            new RawSettings((Map<String, ?>) block),
            authMethodCreatorsRegistry,
            ldapSettingsCollection,
            userGroupsProviderSettingsCollection,
            externalAuthenticationServiceSettingsCollection,
            UserSettingsCollection.from(raw, authMethodCreatorsRegistry)
        ))
        .collect(Collectors.toList());
    this.enable = raw.booleanOpt(ATTRIBUTE_ENABLE).orElse(!blocksSettings.isEmpty());
  }

  public boolean isEnabled() {
    return enable;
  }

  public String getForbiddenMessage() {
    return forbiddenMessage;
  }

  public ImmutableList<BlockSettings> getBlocksSettings() {
    return ImmutableList.copyOf(blocksSettings);
  }
}
