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

import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.settings.AuthKeyProviderSettings;
import org.elasticsearch.plugin.readonlyrest.settings.AuthMethodCreatorsRegistry;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class UserSettings {

  private static final String USERNAME = "username";
  private static final String GROUPS = "groups";

  private final String username;
  private final Set<String> groups;
  private final AuthKeyProviderSettings authKeyProviderSettings;

  @SuppressWarnings("unchecked")
  public UserSettings(RawSettings settings, AuthMethodCreatorsRegistry registry) {
    this.username = settings.stringReq(USERNAME);
    this.groups = (Set<String>) settings.notEmptySetReq(GROUPS);
    List<String> attributes = settings.getKeys().stream()
      .filter(k -> !Sets.newHashSet(USERNAME, GROUPS).contains(k))
      .collect(Collectors.toList());
    if (attributes.size() == 0) {
      throw new SettingsMalformedException("No authentication method defined for user ['" + username + "']");
    }
    else if (attributes.size() > 1) {
      throw new SettingsMalformedException("Only one authentication should be defined for user ['" + username + "']");
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
