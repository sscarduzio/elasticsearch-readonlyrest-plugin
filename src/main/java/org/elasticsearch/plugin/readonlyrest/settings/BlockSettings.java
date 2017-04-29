package org.elasticsearch.plugin.readonlyrest.settings;

import com.google.common.collect.ImmutableList;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ExternalAuthenticationServiceSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ProxyAuthConfigSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettingsCollection;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class BlockSettings {

  public static final String ATTRIBUTE_NAME = "access_control_rules";
  private static final String NAME = "name";

  private final String name;
  private final List<RuleSettings> rules;

  static BlockSettings from(RawSettings settings,
                            LdapSettingsCollection ldapSettingsCollection,
                            UserGroupsProviderSettingsCollection groupsProviderSettingsCollection,
                            ProxyAuthConfigSettingsCollection proxyAuthConfigSettingsCollection,
                            ExternalAuthenticationServiceSettingsCollection externalAuthenticationServiceSettingsCollection) {
    RulesConfigCreatorsRegistry registry = new RulesConfigCreatorsRegistry(
        settings,
        ldapSettingsCollection,
        groupsProviderSettingsCollection,
        proxyAuthConfigSettingsCollection,
        externalAuthenticationServiceSettingsCollection
    );
    String name = settings.stringReq(NAME);
    return new BlockSettings(
        name,
        settings.getKeys().stream()
            .filter(k -> !Objects.equals(k, NAME))
            .map(registry::create)
            .collect(Collectors.toList())
    );
  }

  private BlockSettings(String name, List<RuleSettings> rules) {
    this.name = name;
    this.rules = rules;
  }

  public String getName() {
    return name;
  }

  public ImmutableList<RuleSettings> getRules() {
    return ImmutableList.copyOf(rules);
  }
}
