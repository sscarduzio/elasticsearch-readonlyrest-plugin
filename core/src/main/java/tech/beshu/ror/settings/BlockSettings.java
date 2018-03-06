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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import tech.beshu.ror.acl.BlockPolicy;
import tech.beshu.ror.commons.Verbosity;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.settings.definitions.ExternalAuthenticationServiceSettingsCollection;
import tech.beshu.ror.settings.definitions.LdapSettingsCollection;
import tech.beshu.ror.settings.definitions.UserGroupsProviderSettingsCollection;
import tech.beshu.ror.settings.definitions.UserSettingsCollection;
import tech.beshu.ror.settings.rules.AuthKeyUnixRuleSettings;
import tech.beshu.ror.settings.rules.HostsRuleSettings;
import tech.beshu.ror.settings.rules.SessionMaxIdleRuleSettings;

public class BlockSettings {

  public static final String ATTRIBUTE_NAME = "access_control_rules";

  private static final String NAME = "name";
  private static final String POLICY = "type";
  private static final String VERBOSITY = "verbosity";
  private static final String FILTER = "filter";
  
  public static final Set<String> ruleModifiersToSkip = Sets.newHashSet(
    NAME, POLICY, VERBOSITY, HostsRuleSettings.ATTRIBUTE_ACCEPT_X_FORWARDED_FOR_HEADER,
    AuthKeyUnixRuleSettings.ATTRIBUTE_AUTH_CACHE_TTL, FILTER
  );
  private static final BlockPolicy DEFAULT_BLOCK_POLICY = BlockPolicy.ALLOW;
  private static final Verbosity DEFAULT_VERBOSITY = Verbosity.INFO;
  private final String name;
  private final BlockPolicy policy;
  private final List<RuleSettings> rules;
  private final Verbosity verbosity;
  private final Optional<String> filter;
  
  private BlockSettings(String name, BlockPolicy policy, Verbosity verbosity, List<RuleSettings> rules, Optional<String> filter) {
    validate(rules);
    this.name = name;
    this.policy = policy;
    this.verbosity = verbosity;
    this.rules = rules;
    this.filter = filter;
  }

  public static BlockSettings from(RawSettings settings,
                                   AuthMethodCreatorsRegistry authMethodCreatorsRegistry,
                                   LdapSettingsCollection ldapSettingsCollection,
                                   UserGroupsProviderSettingsCollection groupsProviderSettingsCollection,
                                   ExternalAuthenticationServiceSettingsCollection externalAuthenticationServiceSettingsCollection,
                                   UserSettingsCollection userSettingsCollection) {
    RulesSettingsCreatorsRegistry registry = new RulesSettingsCreatorsRegistry(
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
        .<SettingsMalformedException>orElseThrow(() -> new SettingsMalformedException("Unknown block policy type: " + value)))
      .orElse(DEFAULT_BLOCK_POLICY);
    Verbosity verbosity = settings.stringOpt(VERBOSITY)
      .map(value -> Verbosity.fromString(value)
        .<SettingsMalformedException>orElseThrow(() -> new SettingsMalformedException("Unknown verbosity value: " + value)))
      .orElse(DEFAULT_VERBOSITY);
    Optional<String> filter = settings.stringOpt(FILTER);
    
    return new BlockSettings(
      name,
      policy,
      verbosity,
      settings.getKeys().stream()
        .filter(k -> !ruleModifiersToSkip.contains(k))
        .map(registry::create)
        .collect(Collectors.toList()),
      filter
    );
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

  private void validate(List<RuleSettings> rules) {
    validateIfSessionMaxIdleRuleConfiguredWithUserRule(rules);
  }

  private void validateIfSessionMaxIdleRuleConfiguredWithUserRule(List<RuleSettings> rules) {
    if (rules.stream().anyMatch(r -> r instanceof SessionMaxIdleRuleSettings)) {
      if (rules.stream().noneMatch(r -> r instanceof AuthKeyProviderSettings)) {
        throw new SettingsMalformedException("'" + SessionMaxIdleRuleSettings.ATTRIBUTE_NAME +
                                               "' rule does not mean anything if you don't also set some authentication rule");
      }
    }
  }

  public Verbosity getVerbosity() {
    return verbosity;
  }
  
  public Optional<String> getFilter() {
	  return filter;
  }
}
