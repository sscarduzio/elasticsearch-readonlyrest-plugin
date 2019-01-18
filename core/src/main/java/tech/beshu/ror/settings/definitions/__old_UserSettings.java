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

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.settings.AuthKeyProviderSettings;
import tech.beshu.ror.settings.AuthMethodCreatorsRegistry;
import tech.beshu.ror.settings.BlockSettings;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class __old_UserSettings {

  private static final String USERNAME = "username";
  private static final String GROUPS = "groups";

  private final String username;
  private final Set<String> groups;
  private final AuthKeyProviderSettings authKeyProviderSettings;

  @SuppressWarnings("unchecked")
  public __old_UserSettings(RawSettings settings, AuthMethodCreatorsRegistry registry) {
    this.username = settings.stringReq(USERNAME);
    this.groups = (Set<String>) settings.notEmptySetReq(GROUPS);
    List<String> attributes = settings.getKeys().stream()
      .filter(k -> !Sets.newHashSet(USERNAME, GROUPS).contains(k) && !BlockSettings.ruleModifiersToSkip.contains(k))
      .collect(Collectors.toList());
    if (attributes.size() == 0) {
      throw new SettingsMalformedException("No authentication method defined for user ['" + username + "']");
    }
    else if (attributes.size() > 1) {
      throw new SettingsMalformedException("Only one authentication should be defined for user ['" + username + "']. Found "
                                             + Joiner.on(",").join(attributes));
    }
    this.authKeyProviderSettings = registry.create(attributes.get(0), settings);
  }

  public String getUsername() {
    return username;
  }

  public Set<String> getGroups() {
    return groups;
  }

  public AuthKeyProviderSettings getAuthKeyProviderSettings() {
    return authKeyProviderSettings;
  }
}
