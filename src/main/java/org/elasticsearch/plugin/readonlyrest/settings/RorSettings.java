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

  private static final String ATTRIBUTE_NAME = "readonlyrest";

  private static final String DEFAULT_FORBIDDEN_MESSAGE = "";
  private static final List<BlockSettings> DEFAULT_BLOCK_SETTINGS = Lists.newArrayList();

  private final boolean enable;
  private final String forbiddenMessage;
  private final SSLSettings sslSettings;
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

    this.forbiddenMessage = raw.stringOpt("response_if_req_forbidden").orElse(DEFAULT_FORBIDDEN_MESSAGE);
    this.sslSettings = SSLSettings.from(raw.inner("ssl"));
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
    this.enable = raw.booleanOpt("enable").orElse(!blocksSettings.isEmpty());
  }

  public boolean isEnabled() {
    return enable;
  }

  public String getForbiddenMessage() {
    return forbiddenMessage;
  }

  public SSLSettings getSslSettings() {
    return sslSettings;
  }

  public ImmutableList<BlockSettings> getBlocksSettings() {
    return ImmutableList.copyOf(blocksSettings);
  }
}
