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
package tech.beshu.ror.settings.definitions;

import tech.beshu.ror.commons.settings.RawSettings;

public class __old_GroupsProviderLdapSettings extends AuthenticationLdapSettings {

  public static final String UNIQUE_MEMBER_DEFAULT = "uniqueMember";
  public static final String GROUP_SEARCH_FILTER_DEFAULT = "(cn=*)";
  public static final String GROUP_NAME_ATTRIBUTE_DEFAULT = "cn";
  public static final String SEARCH_GROUPS = "search_groups_base_DN";
  public static final boolean GROUPS_FROM_USER_DEFAULT = false;
  public static final String GROUPS_FROM_USER_ATTRIBUTE_DEFAULT = "memberOf";
  private static final String UNIQUE_MEMBER = "unique_member_attribute";
  private static final String GROUP_SEARCH_FILTER = "group_search_filter";
  private static final String GROUP_NAME_ATTRIBUTE = "group_name_attribute";
  private static final String GROUPS_FROM_USER = "groups_from_user";
  private static final String GROUPS_FROM_USER_ATTRIBUTE = "groups_from_user_attribute";
  private final String searchGroupBaseDn;
  private final String uniqueMemberAttribute;
  private final String groupSearchFilter;
  private final String groupNameAttribute;
  private final boolean groupsFromUser;
  private final String groupsFromUserAttribute;

  public __old_GroupsProviderLdapSettings(RawSettings settings) {
    super(settings);
    this.searchGroupBaseDn = settings.stringReq(SEARCH_GROUPS);
    this.uniqueMemberAttribute = settings.stringOpt(UNIQUE_MEMBER).orElse(UNIQUE_MEMBER_DEFAULT);
    this.groupSearchFilter = settings.stringOpt(GROUP_SEARCH_FILTER).orElse(GROUP_SEARCH_FILTER_DEFAULT);
    this.groupNameAttribute = settings.stringOpt(GROUP_NAME_ATTRIBUTE).orElse(GROUP_NAME_ATTRIBUTE_DEFAULT);
    this.groupsFromUser = settings.booleanOpt(GROUPS_FROM_USER).orElse(GROUPS_FROM_USER_DEFAULT);
    this.groupsFromUserAttribute = settings.stringOpt(GROUPS_FROM_USER_ATTRIBUTE).orElse(GROUPS_FROM_USER_ATTRIBUTE_DEFAULT);
  }

  public static boolean canBeCreated(RawSettings settings) {
    return settings.stringOpt(SEARCH_GROUPS).isPresent();
  }

  public String getGroupNameAttribute() {
    return groupNameAttribute;
  }

  public String getSearchGroupBaseDn() {
    return searchGroupBaseDn;
  }

  public String getUniqueMemberAttribute() {
    return uniqueMemberAttribute;
  }

  public String getGroupSearchFilter() {
    return groupSearchFilter;
  }

  public boolean isGroupsFromUser() {
    return groupsFromUser;
  }

  public String getGroupsFromUserAttribute() {
    return groupsFromUserAttribute;
  }
}
