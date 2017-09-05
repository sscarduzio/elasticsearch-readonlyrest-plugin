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

  private YamlEnabledSslSettings(File keystoreFile, Optional<String> keystorePass, Optional<String> keyPass,
                                 Optional<String> keyAlias, Optional<File> privkeyPem, Optional<File> certchainPem) {
    this.keystoreFile = keystoreFile;
    this.keystorePass = keystorePass;
    this.keyPass = keyPass;
    this.keyAlias = keyAlias;
    this.privkeyPem = privkeyPem;
    this.certchainPem = certchainPem;
  }

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
