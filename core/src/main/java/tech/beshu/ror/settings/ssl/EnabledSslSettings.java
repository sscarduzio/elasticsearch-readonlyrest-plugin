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
package tech.beshu.ror.settings.ssl;

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
