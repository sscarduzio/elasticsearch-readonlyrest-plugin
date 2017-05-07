package org.elasticsearch.plugin.readonlyrest.settings.ssl.impl;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.EnabledSslSettings;

import java.io.File;
import java.util.Optional;

public class YamlEnabledSslSettings implements EnabledSslSettings {

  private final File keystoreFile;
  private final Optional<String> keystorePass;
  private final Optional<String> keyPass;
  private final Optional<String> keyAlias;
  private final Optional<File> privkeyPem;
  private final Optional<File> certchainPem;

  public static EnabledSslSettings from(RawSettings settings) {
    return new YamlEnabledSslSettings(
        new File(settings.stringReq(ATTRIBUTE_KEYSTORE_FILE)),
        settings.stringOpt(ATTRIBUTE_KEYSTORE_PASS),
        settings.stringOpt(ATTRIBUTE_KEY_PASS),
        settings.stringOpt(ATTRIBUTE_KEY_ALIAS),
        settings.stringOpt(ATTRIBUTE_PRIVKEY_PEM).map(File::new),
        settings.stringOpt(ATTRIBUTE_CERTCHAIN_PEM).map(File::new)
    );
  }

  private YamlEnabledSslSettings(File keystoreFile, Optional<String> keystorePass, Optional<String> keyPass,
                                 Optional<String> keyAlias, Optional<File> privkeyPem, Optional<File> certchainPem) {
    this.keystoreFile = keystoreFile;
    this.keystorePass = keystorePass;
    this.keyPass = keyPass;
    this.keyAlias = keyAlias;
    this.privkeyPem = privkeyPem;
    this.certchainPem = certchainPem;
  }

  public File getKeystoreFile() {
    return keystoreFile;
  }

  public Optional<String> getKeystorePass() {
    return keystorePass;
  }

  public Optional<String> getKeyPass() {
    return keyPass;
  }

  public Optional<String> getKeyAlias() {
    return keyAlias;
  }

  public Optional<File> getPrivkeyPem() {
    return privkeyPem;
  }

  public Optional<File> getCertchainPem() {
    return certchainPem;
  }

}
