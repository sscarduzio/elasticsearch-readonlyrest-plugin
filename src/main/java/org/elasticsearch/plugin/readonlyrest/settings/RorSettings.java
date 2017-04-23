package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class RorSettings extends Settings {

  @JsonProperty("enable")
  private boolean enable = true;

  @JsonProperty("response_if_req_forbidden")
  private String forbiddenMessage;

  @JsonProperty(value = "access_control_rules")
  private List<BlockSettings> blocksSettings = Lists.newArrayList();

  @JsonProperty("ldaps")
  private List<LdapSettings> ldapsSettings = Lists.newArrayList();

  @JsonProperty("proxy_auth_configs")
  private List<ProxyAuthSettings> proxyAuthsSettings = Lists.newArrayList();

  @JsonProperty("user_groups_providers")
  private List<UserGroupsProviderSettings> userGroupsProviderSettings = Lists.newArrayList();

  public String getForbiddenMessage() {
    return forbiddenMessage;
  }

  public ImmutableList<BlockSettings> getBlocksSettings() {
    return ImmutableList.copyOf(blocksSettings);
  }

  @Override
  public void configure() {
    blocksSettings.forEach(b -> {
      b.updateWithLdapSettings(ldapsSettings);
    });
  }

  @Override
  protected void validate() {
    blocksSettings.forEach(BlockSettings::validate);

    ldapsSettings.forEach(LdapSettings::validate);
    proxyAuthsSettings.forEach(ProxyAuthSettings::validate);
    userGroupsProviderSettings.forEach(UserGroupsProviderSettings::validate);

    validateNoDuplicates("ldaps",
        ldapsSettings.stream().map(LdapSettings::getName).collect(Collectors.toList()));
    validateNoDuplicates("proxy_auth_configs",
        proxyAuthsSettings.stream().map(ProxyAuthSettings::getName).collect(Collectors.toList()));
    validateNoDuplicates("user_groups_providers",
        userGroupsProviderSettings.stream().map(UserGroupsProviderSettings::getName).collect(Collectors.toList()));
  }

  private void validateNoDuplicates(String section, List<String> names) {
    List<String> duplicates = names.stream()
        .filter(name -> Collections.frequency(names, name) > 1)
        .collect(Collectors.toList());
    if(!duplicates.isEmpty()) {
      throw new ConfigMalformedException("Duplicates found in '" + section + "' definitions: " +
          Joiner.on(",").join(duplicates));
    }
  }
}
