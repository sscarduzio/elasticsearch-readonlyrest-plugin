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

package tech.beshu.ror.commons.settings;

import com.google.common.base.Strings;
import cz.seznam.euphoria.shaded.guava.com.google.common.collect.ImmutableList;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.commons.Verbosity;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.commons.utils.ReflecUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static tech.beshu.ror.commons.Constants.SETTINGS_YAML_FILE;

public class BasicSettings {
  public static final String ROR_YAML_SETTINGS_PATH = System.getProperty(Constants.SETTINGS_YAML_FILE_PATH_PROPERTY);
  public static final String ATTRIBUTE_NAME = "readonlyrest";
  public static final String ATTRIBUTE_ENABLE = "enable";
  public static final String ATTRIBUTE_FORBIDDEN_RESPONSE = "response_if_req_forbidden";
  public static final String ATTRIBUTE_SEARCHLOG = "searchlog";
  public static final String PROMPT_FOR_BASIC_AUTH = "prompt_for_basic_auth";
  public static final String VERBOSITY = "verbosity";
  public static final String AUDIT_COLLECTOR = "audit_collector";
  public static final String CUSTOM_AUDIT_SERIALIZER = "audit_serializer";
  public static final String AUDIT_INDEX_TEMPLATE = "audit_index_template";
  public static final String CACHE_HASHING_ALGO = "cache_hashing_algo";

  // SSL
  public static final String PREFIX_SSL_HTTP = "ssl.";
  public static final String PREFIX_SSL_INTERNODE = "ssl_internode.";
  public static final String ATTRIBUTE_SSL_KEYSTORE_FILE = "keystore_file";
  public static final String ATTRIBUTE_SSL_KEYSTORE_PASS = "keystore_pass";
  public static final String ATTRIBUTE_SSL_KEY_PASS = "key_pass";
  public static final String ATTRIBUTE_SSL_KEY_ALIAS = "key_alias";
  public static final String ATTRIBUTE_SSL_ALLOWED_CIPHERS = "allowed_ciphers";
  public static final String ATTRIBUTE_SSL_ALLOWED_PROTOCOLS = "allowed_protocols";

  private static final String DEFAULT_FORBIDDEN_MESSAGE = "forbidden";
  private static final Verbosity DEFAULT_VERBOSITY = Verbosity.INFO;
  private final boolean enable;
  private final String forbiddenMessage;
  private final Verbosity verbosity;
  private final Boolean auditCollector;
  private final String auditIndexTemplate;
  private final Boolean promptForBasicAuth;

  private final List<?> blocksSettings;
  private final RawSettings raw;
  private final Path configPath;
  private final RawSettings raw_global;
  private final Optional<String> customAuditSerializer;
  private final Optional<String> cacheHashingAlgo;
  private Optional<SSLSettings> sslHttpSettings;
  private Optional<SSLSettings> sslTxpSettings;

  @SuppressWarnings("unchecked")
  public BasicSettings(RawSettings raw_global, Path configPath) {
    this.configPath = configPath;
    this.raw_global = raw_global;
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
    this.customAuditSerializer = raw.opt(CUSTOM_AUDIT_SERIALIZER);
    this.auditIndexTemplate = raw.stringOpt(AUDIT_INDEX_TEMPLATE).orElse(Constants.AUDIT_LOG_DEFAULT_INDEX_TEMPLATE);
    this.cacheHashingAlgo = raw.stringOpt(CACHE_HASHING_ALGO);

    try {
      this.sslHttpSettings = Optional.of(new SSLSettings(raw, PREFIX_SSL_HTTP));
    } catch (Exception e) {
      this.sslHttpSettings = Optional.empty();
    }

    try {
      this.sslTxpSettings = Optional.of(new SSLSettings(raw, PREFIX_SSL_INTERNODE));
    } catch (Exception e) {
      this.sslTxpSettings = Optional.empty();
    }
  }

  private static String slurpFile(LoggerShim logger, String filePath) {

    final String[] slurped = new String[1];
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      try {
        slurped[0] = new String(Files.readAllBytes(Paths.get(filePath)));
        logger.debug("Read data from " + filePath);
      } catch (Throwable t) {
        logger.info(
            "Could not find settings in "
                + filePath + " (" + t.getMessage() + ")");

      }
      return null;
    });
    return slurped[0];
  }

  public static BasicSettings fromFileObj(LoggerShim logger, Path configPath, Object settingsObject) {
    logger.debug("reading settings path (file obj) " + configPath);
    if (!Strings.isNullOrEmpty(ROR_YAML_SETTINGS_PATH)) {
      logger.info("overriding  settings path to " + ROR_YAML_SETTINGS_PATH);
      configPath = Paths.get(ROR_YAML_SETTINGS_PATH);
    }
    return fromFile(logger, configPath, (Map<String, ?>) ReflecUtils.invokeMethodCached(settingsObject, settingsObject.getClass(), "getAsStructuredMap"));
  }

  public static BasicSettings fromFile(LoggerShim logger, Path configPath, Map<String, ?> fallback) {
    logger.debug("reading settings path (file obj) " + configPath);
    if (!Strings.isNullOrEmpty(ROR_YAML_SETTINGS_PATH)) {
      logger.info("overriding settings  path to " + ROR_YAML_SETTINGS_PATH);
      configPath = Paths.get(ROR_YAML_SETTINGS_PATH);
    }

    try {
      final String baseConfigDirPath = configPath.toAbsolutePath().toString();
      final String rorSettingsFilePath = Constants.makeAbsolutePath(SETTINGS_YAML_FILE, baseConfigDirPath);

      String s4s = slurpFile(logger, rorSettingsFilePath);
      try {
        if (SettingsUtils.yaml2Map(s4s, logger).containsKey("readonlyrest")) {
          return new BasicSettings(new RawSettings(s4s, logger), configPath);
        }
        return new BasicSettings(new RawSettings(fallback, logger), configPath);
      } catch (Throwable t) {
        logger.error("cannot parse settings file ", t);
        return new BasicSettings(new RawSettings(fallback, logger), configPath);
      }

    } catch (Throwable t) {
      t.printStackTrace();
      throw t;
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

  public String getAuditIndexTemplate() {
    return auditIndexTemplate;
  }

  public Optional<String> getCustomAuditSerializer() {
    return customAuditSerializer;
  }

  public String getCacheHashingAlgo() {
    return cacheHashingAlgo.orElse("none");
  }

  public Boolean isPromptForBasicAuth() {
    return promptForBasicAuth;
  }

  public RawSettings getRaw() {
    return raw_global;
  }

  public Map<String, ?> asMap() {
    return raw.asMap();
  }

  public Optional<SSLSettings> getSslHttpSettings() {
    return this.sslHttpSettings;
  }

  public Optional<SSLSettings> getSslInternodeSettings() {
    return this.sslTxpSettings;
  }

  public class SSLSettings {
    private final boolean sslEnabled;
    private Optional<String> keystorePass;
    private Optional<String> keyPass;
    private Optional<List<String>> allowedSSLCiphers = Optional.empty();
    private Optional<List<String>> allowedSSLProtocols = Optional.empty();
    private Optional<String> keyAlias;
    private String keystoreFile;

    public SSLSettings(RawSettings raw, String prefix) {
      // SSL
      Optional<RawSettings> sslSettingsOpt = raw.innerOpt(prefix.replaceFirst(".$", ""));
      Optional sslEnableOpt = raw.booleanOpt(prefix + "enable");
      Optional<String> ksOpt = raw.stringOpt(prefix + ATTRIBUTE_SSL_KEYSTORE_FILE);

      if (!sslSettingsOpt.isPresent() || (sslEnableOpt.isPresent() && sslEnableOpt.get().equals(false)) || !ksOpt.isPresent()) {
        this.sslEnabled = false;
      }
      else {
        this.sslEnabled = true;
      }

      if (sslEnabled) {
        this.keystoreFile = Constants.makeAbsolutePath(raw.stringReq(prefix + ATTRIBUTE_SSL_KEYSTORE_FILE), configPath.toAbsolutePath().toString());
        this.keyAlias = raw.stringOpt(prefix + ATTRIBUTE_SSL_KEY_ALIAS);
        this.keyPass = raw.stringOpt(prefix + ATTRIBUTE_SSL_KEY_PASS);
        this.keystorePass = raw.stringOpt(prefix + ATTRIBUTE_SSL_KEYSTORE_PASS);
        this.allowedSSLCiphers = raw.opt(prefix + ATTRIBUTE_SSL_ALLOWED_CIPHERS);
        this.allowedSSLProtocols = raw.opt(prefix + ATTRIBUTE_SSL_ALLOWED_PROTOCOLS);
      }
    }

    public Optional<List<String>> getAllowedSSLCiphers() {
      return allowedSSLCiphers;
    }

    public Optional<List<String>> getAllowedSSLProtocols() {
      return allowedSSLProtocols;
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
}
