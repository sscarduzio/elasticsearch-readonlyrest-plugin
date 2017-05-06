package org.elasticsearch.plugin.readonlyrest.settings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.acl.BlockPolicy;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ExternalAuthenticationServiceSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.rules.HostsRuleSettings;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class BlockSettings {

  public static final String ATTRIBUTE_NAME = "access_control_rules";

  private static final String NAME = "name";
  private static final String POLICY = "type";

  private static final BlockPolicy DEFAULT_BLOCK_POLICY = BlockPolicy.ALLOW;

  private final String name;
  private final BlockPolicy policy;
  private final List<RuleSettings> rules;

  public static BlockSettings from(RawSettings settings,
                                   AuthMethodCreatorsRegistry authMethodCreatorsRegistry,
                                   LdapSettingsCollection ldapSettingsCollection,
                                   UserGroupsProviderSettingsCollection groupsProviderSettingsCollection,
                                   ExternalAuthenticationServiceSettingsCollection externalAuthenticationServiceSettingsCollection,
                                   UserSettingsCollection userSettingsCollection) {
    RulesConfigCreatorsRegistry registry = new RulesConfigCreatorsRegistry(
        settings,
        authMethodCreatorsRegistry,
        ldapSettingsCollection,
        groupsProviderSettingsCollection,
        externalAuthenticationServiceSettingsCollection,
        userSettingsCollection
    );
    String name = settings.stringReq(NAME);
    BlockPolicy policy = settings.stringOpt(POLICY)
        .map(value -> BlockPolicy.fromString(value)
            .<ConfigMalformedException>orElseThrow(() -> new ConfigMalformedException("Unknown block policy type: " + value)))
        .orElse(DEFAULT_BLOCK_POLICY);
    Set<String> filteredBlockAttributes = Sets.newHashSet(
        NAME, POLICY, HostsRuleSettings.ATTRIBUTE_ACCEPT_X_FORWARDED_FOR_HEADER
    );
    return new BlockSettings(
        name,
        policy,
        settings.getKeys().stream()
            .filter(k -> !filteredBlockAttributes.contains(k))
            .map(registry::create)
            .collect(Collectors.toList())
    );
  }

  private BlockSettings(String name, BlockPolicy policy, List<RuleSettings> rules) {
    this.name = name;
    this.policy = policy;
    this.rules = rules;
  }

  public String getName() {
    return name;
  }

  public BlockPolicy getPolicy() {
    return policy;
  }

  public ImmutableList<RuleSettings> getRules() {
    return ImmutableList.copyOf(rules);
  }
}
