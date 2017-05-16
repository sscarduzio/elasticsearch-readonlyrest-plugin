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

public class UserGroupsSearchFilterConfig {

  private final String searchGroupBaseDN;
  private final String uniqueMemberAttribute;

  public UserGroupsSearchFilterConfig(String searchGroupBaseDN, String uniqueMemberAttribute) {
    this.searchGroupBaseDN = searchGroupBaseDN;
    this.uniqueMemberAttribute = uniqueMemberAttribute;
  }

  public String getSearchGroupBaseDN() {
    return searchGroupBaseDN;
  }

  public String getUniqueMemberAttribute() {
    return uniqueMemberAttribute;
  }

  public static class Builder {

    public static String DEFAULT_UNIQUE_MEMBER_ATTRIBUTE = "uniqueMember";

    private final String searchGroupBaseDN;
    private String uniqueMemberAttribute = DEFAULT_UNIQUE_MEMBER_ATTRIBUTE;

    public Builder(String searchGroupBaseDN) {
      this.searchGroupBaseDN = searchGroupBaseDN;
    }

    public Builder setUniqueMemberAttribute(String uniqueMemberAttribute) {
      this.uniqueMemberAttribute = uniqueMemberAttribute;
      return this;
    }

    public UserGroupsSearchFilterConfig build() {
      return new UserGroupsSearchFilterConfig(searchGroupBaseDN, uniqueMemberAttribute);
    }
  }
}
