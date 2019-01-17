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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import tech.beshu.ror.acl.blocks.rules.impl.LdapAuthorizationAsyncRule;
import tech.beshu.ror.commons.Verbosity;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.settings.definitions.ExternalAuthenticationServiceSettingsCollection;
import tech.beshu.ror.settings.definitions.JwtAuthDefinitionSettingsCollection;
import tech.beshu.ror.settings.definitions.LdapSettingsCollection;
import tech.beshu.ror.settings.definitions.ProxyAuthDefinitionSettingsCollection;
import tech.beshu.ror.settings.definitions.RorKbnAuthDefinitionSettingsCollection;
import tech.beshu.ror.settings.definitions.UserGroupsProviderSettingsCollection;
import tech.beshu.ror.settings.definitions.UserSettingsCollection;
import tech.beshu.ror.settings.rules.LdapAuthRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthorizationRuleSettings;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class RorSettings {
  public static final String ATTRIBUTE_NAME = "readonlyrest";
  public static final String ATTRIBUTE_ENABLE = "enable";
  public static final String ATTRIBUTE_FORBIDDEN_RESPONSE = "response_if_req_forbidden";
  public static final String ATTRIBUTE_SEARCHLOG = "searchlog";
  public static final String PROMPT_FOR_BASIC_AUTH = "prompt_for_basic_auth";
  public static final String VERBOSITY = "verbosity";
  public static final String AUDIT_COLLECTOR = "audit_collector";

  // SSL
  public static final String PREFIX_SSL = "ssl.";
  public static final String ATTRIBUTE_SSL_KEYSTORE_FILE = "keystore_file";
  public static final String ATTRIBUTE_SSL_KEYSTORE_PASS = "keystore_pass";
  public static final String ATTRIBUTE_SSL_KEY_PASS = "key_pass";
  public static final String ATTRIBUTE_SSL_KEY_ALIAS = "key_alias";
  private static final String DEFAULT_FORBIDDEN_MESSAGE = "";
  private static final List<BlockSettings> DEFAULT_BLOCK_SETTINGS = Lists.newArrayList();
  private static final Verbosity DEFAULT_VERBOSITY = Verbosity.INFO;
  private final boolean enable;
  private final String forbiddenMessage;
  private final Verbosity verbosity;
  private final Boolean auditCollector;
  private final List<BlockSettings> blocksSettings;
  private final Boolean promptForBasicAuth;
  private final boolean sslEnabled;
  private Optional<String> keystorePass;
  private Optional<String> keyPass;
  private Optional<String> keyAlias;
  private String keystoreFile;
  private Map<String, Set<String>> ldapConnector2allowedGroups;

  @SuppressWarnings("unchecked")
  public RorSettings(RawSettings raw_global) {
    final RawSettings raw = raw_global.opt(ATTRIBUTE_NAME).isPresent() ? raw_global.inner(ATTRIBUTE_NAME) : raw_global;

    LdapSettingsCollection ldapSettingsCollection = LdapSettingsCollection.from(raw);
    UserGroupsProviderSettingsCollection userGroupsProviderSettingsCollection = UserGroupsProviderSettingsCollection.from(raw);
    ProxyAuthDefinitionSettingsCollection proxyAuthDefinitionSettingsCollection = ProxyAuthDefinitionSettingsCollection.from(raw);
    ExternalAuthenticationServiceSettingsCollection externalAuthenticationServiceSettingsCollection = ExternalAuthenticationServiceSettingsCollection.from(raw);
    JwtAuthDefinitionSettingsCollection jwtAuthDefinitionSettingsCollection = JwtAuthDefinitionSettingsCollection.from(raw);
    RorKbnAuthDefinitionSettingsCollection rorKbnAuthDefinitionSettingsCollection = RorKbnAuthDefinitionSettingsCollection.from(raw);
    AuthMethodCreatorsRegistry authMethodCreatorsRegistry = new AuthMethodCreatorsRegistry(
        proxyAuthDefinitionSettingsCollection,
        ldapSettingsCollection,
        jwtAuthDefinitionSettingsCollection,
        rorKbnAuthDefinitionSettingsCollection
    );

    this.forbiddenMessage = raw.stringOpt(ATTRIBUTE_FORBIDDEN_RESPONSE).orElse(DEFAULT_FORBIDDEN_MESSAGE);
    this.blocksSettings = raw.notEmptyListOpt(BlockSettings.ATTRIBUTE_NAME).orElse(DEFAULT_BLOCK_SETTINGS).stream()
                             .map(block -> BlockSettings.from(
                                 new RawSettings((Map<String, ?>) block, raw.getLogger()),
                                 authMethodCreatorsRegistry,
                                 ldapSettingsCollection,
                                 userGroupsProviderSettingsCollection,
                                 externalAuthenticationServiceSettingsCollection,
                                 UserSettingsCollection.from(raw, authMethodCreatorsRegistry)
                             ))
                             .collect(Collectors.toList());

    List<String> blockNames = this.blocksSettings.stream().map(BlockSettings::getName).collect(Collectors.toList());
    blockNames.forEach(name -> {
      if (Collections.frequency(blockNames, name) > 1) {
        throw new SettingsMalformedException("__old_ACL __old_Block names should be unique! Found more than one __old_ACL block with the same name: " + name);
      }
    });

    // This is useful for LDAP AUTHZ multitenancy available groups list
    this.ldapConnector2allowedGroups = new HashMap<>();
    for (BlockSettings bs : blocksSettings) {
      Optional<Map.Entry<String, Set<String>>> ldapAuthorizationAsyncRuleO = bs
          .getRules()
          .stream()
          .map(r -> {
            if (r instanceof LdapAuthorizationAsyncRule) {
              LdapAuthorizationRuleSettings s = (LdapAuthorizationRuleSettings) r;
              return Maps.immutableEntry(s.getLdapSettings().getName(), s.getGroups());
            }
            else if (r instanceof LdapAuthRuleSettings) {
              LdapAuthRuleSettings s = (LdapAuthRuleSettings) r;
              return Maps.immutableEntry(s.getLdapSettings().getName(), s.getGroups());
            }
            else {
              return null;
            }
          })
          .filter(Objects::nonNull)
          .findFirst();

      ldapAuthorizationAsyncRuleO.ifPresent(entry -> {
        String ldapConnectorName = entry.getKey();
        Set<String> groupsForThisLdapConnector = ldapConnector2allowedGroups.getOrDefault(ldapConnectorName, Sets.newHashSet());
        groupsForThisLdapConnector.addAll(entry.getValue());
        ldapConnector2allowedGroups.put(ldapConnectorName, groupsForThisLdapConnector);
      });
    }

    ldapConnector2allowedGroups
        .keySet().stream()
        .forEach(connectorName -> ldapSettingsCollection.get(connectorName).setAvailableGroups(ldapConnector2allowedGroups.get(connectorName)));

    this.enable = raw.booleanOpt(ATTRIBUTE_ENABLE).orElse(!blocksSettings.isEmpty());
    this.promptForBasicAuth = raw.booleanOpt(PROMPT_FOR_BASIC_AUTH).orElse(true);
    this.verbosity = raw.stringOpt(VERBOSITY)
                        .map(value -> Verbosity.fromString(value)
                            .<SettingsMalformedException>orElseThrow(() -> new SettingsMalformedException("Unknown verbosity value: " + value)))
                        .orElse(DEFAULT_VERBOSITY);
    this.auditCollector = raw.booleanOpt(AUDIT_COLLECTOR).orElse(false);

    // SSL
    Optional<RawSettings> sslSettingsOpt = raw.innerOpt(PREFIX_SSL.replaceFirst(".$", ""));
    Optional sslEnableOpt = raw.booleanOpt(PREFIX_SSL + "enable");
    Optional<String> ksOpt = raw.stringOpt(PREFIX_SSL + ATTRIBUTE_SSL_KEYSTORE_FILE);

    if (!sslSettingsOpt.isPresent() || (sslEnableOpt.isPresent() && sslEnableOpt.get().equals(false)) || !ksOpt.isPresent()) {
      this.sslEnabled = false;
    }
    else {
      this.sslEnabled = true;
    }

    if (sslEnabled) {
      this.keystoreFile = raw.stringReq(PREFIX_SSL + ATTRIBUTE_SSL_KEYSTORE_FILE);
      this.keyAlias = raw.stringOpt(PREFIX_SSL + ATTRIBUTE_SSL_KEY_ALIAS);
      this.keyPass = raw.stringOpt(PREFIX_SSL + ATTRIBUTE_SSL_KEY_PASS);
      this.keystorePass = raw.stringOpt(PREFIX_SSL + ATTRIBUTE_SSL_KEYSTORE_PASS);
    }
  }

  public static RorSettings from(RawSettings settings) {
    return new RorSettings(settings);
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

  public Boolean getAuditCollector() {
    return auditCollector;
  }

  public Boolean isPromptForBasicAuth() {
    return promptForBasicAuth;
  }

  public Boolean isSSLEnabled() {
    return sslEnabled;
  }

  public Optional<String> getKeystorePass() {
    return keystorePass;
  }

  public String getKeystoreFile() {
    return keystoreFile;
  }

  public Optional<String> getKeyPass() {
    return keyPass;
  }

  public Optional<String> getKeyAlias() {
    return keyAlias;
  }
}
