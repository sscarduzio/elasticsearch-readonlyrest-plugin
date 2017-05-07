package org.elasticsearch.plugin.readonlyrest.settings.ssl;

import java.io.File;
import java.util.Optional;

public interface EnabledSslSettings extends SslSettings {

  String ATTRIBUTE_KEYSTORE_FILE = "keystore_file";
  String ATTRIBUTE_KEYSTORE_PASS = "keystore_pass";
  String ATTRIBUTE_KEY_PASS = "key_pass";
  String ATTRIBUTE_KEY_ALIAS = "key_alias";
  String ATTRIBUTE_PRIVKEY_PEM = "privkey_pem";
  String ATTRIBUTE_CERTCHAIN_PEM = "certchain_pem";

  File getKeystoreFile();

  Optional<String> getKeystorePass();

  Optional<String> getKeyPass();

  Optional<String> getKeyAlias();

  Optional<File> getPrivkeyPem();

  Optional<File> getCertchainPem();
}
