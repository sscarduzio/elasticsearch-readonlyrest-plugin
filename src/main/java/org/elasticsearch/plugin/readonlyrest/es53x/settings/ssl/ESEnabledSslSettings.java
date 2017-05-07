package org.elasticsearch.plugin.readonlyrest.es53x.settings.ssl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.EnabledSslSettings;

import java.io.File;
import java.util.Optional;

public class ESEnabledSslSettings implements EnabledSslSettings {

  private final File keystoreFile;
  private final Optional<String> keystorePass;
  private final Optional<String> keyPass;
  private final Optional<String> keyAlias;
  private final Optional<File> privkeyPem;
  private final Optional<File> certchainPem;

  ESEnabledSslSettings(Settings s) {
    keystoreFile = new File(s.get(ATTRIBUTE_KEYSTORE_FILE));
    keystorePass = Optional.ofNullable(s.get(ATTRIBUTE_KEYSTORE_PASS));
    keyPass = Optional.ofNullable(s.get(ATTRIBUTE_KEY_PASS));
    keyAlias = Optional.ofNullable(s.get(ATTRIBUTE_KEY_ALIAS));
    privkeyPem = Optional.ofNullable(s.get(ATTRIBUTE_PRIVKEY_PEM)).map(File::new);
    certchainPem = Optional.ofNullable(s.get(ATTRIBUTE_CERTCHAIN_PEM)).map(File::new);
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
