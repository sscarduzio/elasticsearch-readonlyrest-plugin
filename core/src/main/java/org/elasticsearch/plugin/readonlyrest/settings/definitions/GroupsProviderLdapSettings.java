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
package org.elasticsearch.plugin.readonlyrest.settings.definitions;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;

public class GroupsProviderLdapSettings extends AuthenticationLdapSettings {

  public static final String SEARCH_GROUPS = "search_groups_base_DN";
  private static final String UNIQUE_MEMBER = "unique_member_attribute";
  private static final String GROUP_SEARCH_FILTER = "group_search_filter";
  private static final String GROUP_NAME_ATTRIBUTE = "group_name_attribute";

  private final String searchGroupBaseDn;
  private final String uniqueMemberAttribute;
  private final String groupSearchFilter;
  private final String groupNameAttribute;

  public GroupsProviderLdapSettings(RawSettings settings) {
    super(settings);
    this.searchGroupBaseDn = settings.stringReq(SEARCH_GROUPS);
    this.uniqueMemberAttribute = settings.stringOpt(UNIQUE_MEMBER).orElse("uniqueMember");
    this.groupSearchFilter = settings.stringOpt(GROUP_SEARCH_FILTER).orElse("cn=*");
    this.groupNameAttribute = settings.stringOpt(GROUP_NAME_ATTRIBUTE).orElse("cn");
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
}
