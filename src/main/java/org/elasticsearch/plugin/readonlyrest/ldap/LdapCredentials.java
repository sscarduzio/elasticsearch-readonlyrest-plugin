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

package org.elasticsearch.plugin.readonlyrest.ldap;

import com.google.common.hash.Hashing;

import java.nio.charset.Charset;
import java.util.Objects;

public class LdapCredentials {

  private final String userName;
  private final String password;

  public LdapCredentials(String userName, String password) {
    this.userName = userName;
    this.password = password;
  }

  public String getUserName() {
    return userName;
  }

  public String getPassword() {
    return password;
  }

  public String getHashedPassword() {
    return Hashing.sha256().hashString(password, Charset.defaultCharset()).toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    final LdapCredentials other = (LdapCredentials) obj;
    return Objects.equals(this.userName, other.userName)
      && Objects.equals(this.password, other.password);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.userName, this.password);
  }
}
