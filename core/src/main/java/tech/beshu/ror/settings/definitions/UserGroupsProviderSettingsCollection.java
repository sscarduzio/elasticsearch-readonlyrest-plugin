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

import com.google.common.collect.Lists;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jooq.lambda.Seq.seq;

public class UserGroupsProviderSettingsCollection {

  public static final String ATTRIBUTE_NAME = "user_groups_providers";

  private final Map<String, UserGroupsProviderSettings> userGroupsProviderSettingsMap;

  private UserGroupsProviderSettingsCollection(List<UserGroupsProviderSettings> groupsProviderSettingsList) {
    validate(groupsProviderSettingsList);
    this.userGroupsProviderSettingsMap = seq(groupsProviderSettingsList).toMap(UserGroupsProviderSettings::getName, Function.identity());
  }

  @SuppressWarnings("unchecked")
  public static UserGroupsProviderSettingsCollection from(RawSettings data) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
      .map(list ->
             list.stream()
               .map(l -> new UserGroupsProviderSettings(new RawSettings((Map<String, ?>) l, data.getLogger())))
               .collect(Collectors.toList())
      )
      .map(UserGroupsProviderSettingsCollection::new)
      .orElse(new UserGroupsProviderSettingsCollection(Lists.newArrayList()));
  }

  public UserGroupsProviderSettings get(String name) {
    if (!userGroupsProviderSettingsMap.containsKey(name))
      throw new SettingsMalformedException("Cannot find User Groups Provider definition with name '" + name + "'");
    return userGroupsProviderSettingsMap.get(name);
  }

  private void validate(List<UserGroupsProviderSettings> grouspProviderSettings) {
    List<String> names = seq(grouspProviderSettings).map(UserGroupsProviderSettings::getName).collect(Collectors.toList());
    if (names.stream().distinct().count() != names.size()) {
      throw new SettingsMalformedException("Duplicated User Groups Provider name in '" + ATTRIBUTE_NAME + "' section");
    }
  }
}
