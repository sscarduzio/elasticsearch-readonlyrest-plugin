package org.elasticsearch.plugin.readonlyrest.settings.ssl;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.SSLSettings;

import java.io.File;
import java.util.Optional;

public class EnabledSSLSettings implements SSLSettings {

  private final File keystoreFile;
  private final String keystorePass;
  private final String keyPass;
  private final Optional<String> keyAlias;
  private final Optional<File> privkeyPem;
  private final Optional<File> certchainPem;

  public static EnabledSSLSettings from(RawSettings settings) {
    return new EnabledSSLSettings(
        new File(settings.stringReq("keystore_file")),
        settings.stringReq("keystore_pass"),
        settings.stringReq("key_pass"),
        settings.stringOpt("key_alias"),
        settings.stringOpt("privkey_pem").map(File::new),
        settings.stringOpt("certchain_pem").map(File::new)
    );
  }

  private EnabledSSLSettings(File keystoreFile, String keystorePass, String keyPass, Optional<String> keyAlias,
                             Optional<File> privkeyPem, Optional<File> certchainPem) {
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

  public String getKeystorePass() {
    return keystorePass;
  }

  public String getKeyPass() {
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
