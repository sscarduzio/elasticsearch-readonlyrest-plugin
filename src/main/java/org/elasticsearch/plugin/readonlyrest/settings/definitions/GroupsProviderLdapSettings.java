package org.elasticsearch.plugin.readonlyrest.settings.definitions;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;

public class GroupsProviderLdapSettings extends LdapSettings {

  public static final String SEARCH_GROUPS = "search_groups_base_DN";
  private static final String UNIQUE_MEMBER = "unique_member_attribute";

  private static final String DEFAULT_UNIQUE_MEMBER_ATTRIBUTE = "uniqueMember";

  private final String searchGroupBaseDn;
  private final String uniqueMemberAttribute;

  public GroupsProviderLdapSettings(RawSettings settings) {
    super(settings);
    this.searchGroupBaseDn = settings.stringReq(SEARCH_GROUPS);
    this.uniqueMemberAttribute = settings.stringOpt(UNIQUE_MEMBER).orElse(DEFAULT_UNIQUE_MEMBER_ATTRIBUTE);
  }

  public String getSearchGroupBaseDn() {
    return searchGroupBaseDn;
  }

  public String getUniqueMemberAttribute() {
    return uniqueMemberAttribute;
  }

  public static boolean canBeCreated(RawSettings settings) {
    return settings.stringOpt(SEARCH_GROUPS).isPresent();
  }
}
