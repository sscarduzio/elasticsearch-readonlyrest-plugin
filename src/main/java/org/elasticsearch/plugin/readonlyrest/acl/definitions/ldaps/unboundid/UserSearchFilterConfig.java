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
package org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid;

public class UserSearchFilterConfig {

  private final String searchUserBaseDN;
  private final String uidAttribute;

  public  UserSearchFilterConfig(String searchUserBaseDN, String uidAttribute) {
    this.searchUserBaseDN = searchUserBaseDN;
    this.uidAttribute = uidAttribute;
  }

  public String getSearchUserBaseDN() {
    return searchUserBaseDN;
  }

  public String getUidAttribute() {
    return uidAttribute;
  }

  public static class Builder {

    public static String DEFAULT_UID_ATTRIBUTE = "uid";

    private final String searchUserBaseDN;
    private String uidAttribute = DEFAULT_UID_ATTRIBUTE;

    public Builder(String searchUserBaseDN) {
      this.searchUserBaseDN = searchUserBaseDN;
    }

    public Builder setUidAttribute(String uidAttribute) {
      this.uidAttribute = uidAttribute;
      return this;
    }

    public UserSearchFilterConfig build() {
      return new UserSearchFilterConfig(searchUserBaseDN, uidAttribute);
    }
  }
}
