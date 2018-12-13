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
package tech.beshu.ror.settings.rules;

import tech.beshu.ror.commons.domain.__old_Value;
import tech.beshu.ror.settings.RuleSettings;
import tech.beshu.ror.settings.definitions.UserSettings;
import tech.beshu.ror.settings.definitions.UserSettingsCollection;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GroupsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "groups";

  private final List<UserSettings> usersSettings;
  private final Set<__old_Value<String>> groups;

  private GroupsRuleSettings(Set<String> groups, List<UserSettings> usersSettings) {
    this.usersSettings = usersSettings;
    this.groups = groups.stream().map(g -> __old_Value.fromString(g, Function.identity())).collect(Collectors.toSet());
  }

  public static GroupsRuleSettings from(Set<String> groups, UserSettingsCollection userSettingsCollection) {
    return new GroupsRuleSettings(groups, userSettingsCollection.getAll());
  }

  public List<UserSettings> getUsersSettings() {
    return usersSettings;
  }

  public Set<__old_Value<String>> getGroups() {
    return groups;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
