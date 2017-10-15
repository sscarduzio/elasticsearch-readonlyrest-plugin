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
package tech.beshu.ror.commons;


import cz.seznam.euphoria.shaded.guava.com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BasicSettings {
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
  private static final Verbosity DEFAULT_VERBOSITY = Verbosity.INFO;
  private final boolean enable;
  private final String forbiddenMessage;
  private final Verbosity verbosity;
  private final Boolean auditCollector;
  private final Boolean promptForBasicAuth;
  private final boolean sslEnabled;
  private final List<?> blocksSettings;
  private final RawSettings raw;
  private Optional<String> keystorePass;
  private Optional<String> keyPass;

  private Optional<String> keyAlias;
  private String keystoreFile;

  @SuppressWarnings("unchecked")
  public BasicSettings(RawSettings raw_global) {

    this.raw = raw_global.inner(ATTRIBUTE_NAME);
    this.forbiddenMessage = raw.stringOpt(ATTRIBUTE_FORBIDDEN_RESPONSE).orElse(DEFAULT_FORBIDDEN_MESSAGE);
    this.blocksSettings = raw.notEmptyListOpt("access_control_rules").orElse(new ArrayList<>(0));
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
      this.keystoreFile = Constants.makeAbsolutePath(raw.stringReq(PREFIX_SSL + ATTRIBUTE_SSL_KEYSTORE_FILE));
      this.keyAlias = raw.stringOpt(PREFIX_SSL + ATTRIBUTE_SSL_KEY_ALIAS);
      this.keyPass = raw.stringOpt(PREFIX_SSL + ATTRIBUTE_SSL_KEY_PASS);
      this.keystorePass = raw.stringOpt(PREFIX_SSL + ATTRIBUTE_SSL_KEYSTORE_PASS);
    }
  }


  public boolean isEnabled() {
    return enable;
  }

  public String getForbiddenMessage() {
    return forbiddenMessage;
  }

  public ImmutableList<?> getBlocksSettings() {
    return ImmutableList.copyOf(blocksSettings);
  }

  public Boolean isAuditorCollectorEnabled() {
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

  public RawSettings getRaw() {
    return raw;
  }
}
