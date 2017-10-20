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
  private final boolean isGroupsFromUser;
  private final String groupsFromUserAttribute;

  public UserGroupsSearchFilterConfig(String searchGroupBaseDN, String uniqueMemberAttribute,
                                      String groupSearchFilter, String groupNameAttribute,
                                      boolean isGroupsFromUser, String groupsFromUserAttribute) {
    this.searchGroupBaseDN = searchGroupBaseDN;
    this.uniqueMemberAttribute = uniqueMemberAttribute;
    this.groupSearchFilter = groupSearchFilter;
    this.groupNameAttribute = groupNameAttribute;
    this.isGroupsFromUser = isGroupsFromUser;
    this.groupsFromUserAttribute = groupsFromUserAttribute;
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

  public boolean isGroupsFromUser () {
    return isGroupsFromUser;
  }

  public String getGroupsFromUserAttribute() {
    return groupsFromUserAttribute;
  }

  public static class Builder {

    private final String searchGroupBaseDN;
    private String uniqueMemberAttribute = GroupsProviderLdapSettings.UNIQUE_MEMBER_DEFAULT;
    private String groupSearchFilter = GroupsProviderLdapSettings.GROUP_SEARCH_FILTER_DEFAULT;
    private String groupNameAttribute = GroupsProviderLdapSettings.GROUP_NAME_ATTRIBUTE_DEFAULT;
    private boolean isGroupsFromUser = GroupsProviderLdapSettings.GROUPS_FROM_USER_DEFAULT;
    private String groupsFromUserAttribute = GroupsProviderLdapSettings.GROUPS_FROM_USER_ATTRIBUTE_DEFAULT;

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

    public Builder setIsGroupsFromUser(boolean isGroupsFromUser) {
      this.isGroupsFromUser = isGroupsFromUser;
      return this;
    }

    public Builder setGroupsFromUserAttribute(String groupsFromUserAttribute) {
      this.groupsFromUserAttribute = groupsFromUserAttribute;
      return this;
    }

    public UserGroupsSearchFilterConfig build() {
      return new UserGroupsSearchFilterConfig(searchGroupBaseDN, uniqueMemberAttribute, groupSearchFilter, groupNameAttribute, isGroupsFromUser, groupsFromUserAttribute);
    }
  }
}
