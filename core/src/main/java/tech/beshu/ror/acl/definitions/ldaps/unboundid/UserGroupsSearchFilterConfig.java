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
package tech.beshu.ror.acl.definitions.ldaps.unboundid;

import tech.beshu.ror.settings.definitions.GroupsProviderLdapSettings;

public class UserGroupsSearchFilterConfig {

  private final String searchGroupBaseDN;
  private final String uniqueMemberAttribute;
  private final String groupSearchFilter;
  private final String groupNameAttribute;

  public UserGroupsSearchFilterConfig(String searchGroupBaseDN, String uniqueMemberAttribute,
                                      String groupSearchFilter, String groupNameAttribute) {
    this.searchGroupBaseDN = searchGroupBaseDN;
    this.uniqueMemberAttribute = uniqueMemberAttribute;
    this.groupSearchFilter = groupSearchFilter;
    this.groupNameAttribute = groupNameAttribute;
  }

  public String getGroupNameAttribute() {
    return groupNameAttribute;
  }

  public String getGroupSearchFilter() {
    return groupSearchFilter;
  }

  public String getSearchGroupBaseDN() {
    return searchGroupBaseDN;
  }

  public String getUniqueMemberAttribute() {
    return uniqueMemberAttribute;
  }

  public static class Builder {

    private final String searchGroupBaseDN;
    private String uniqueMemberAttribute = GroupsProviderLdapSettings.UNIQUE_MEMBER_DEFAULT;
    private String groupSearchFilter = GroupsProviderLdapSettings.GROUP_SEARCH_FILTER_DEFAULT;
    private String groupNameAttribute = GroupsProviderLdapSettings.GROUP_NAME_ATTRIBUTE_DEFAULT;

    public Builder(String searchGroupBaseDN) {
      this.searchGroupBaseDN = searchGroupBaseDN;
    }

    public Builder setUniqueMemberAttribute(String uniqueMemberAttribute) {
      this.uniqueMemberAttribute = uniqueMemberAttribute;
      return this;
    }

    public Builder setGroupSearchFilter(String groupSearchFilter) {
      this.groupSearchFilter = groupSearchFilter;
      return this;
    }

    public Builder setGroupNameAttribute(String groupNameAttribute) {
      this.groupNameAttribute = groupNameAttribute;
      return this;
    }

    public UserGroupsSearchFilterConfig build() {
      return new UserGroupsSearchFilterConfig(searchGroupBaseDN, uniqueMemberAttribute, groupSearchFilter, groupNameAttribute);
    }
  }
}
