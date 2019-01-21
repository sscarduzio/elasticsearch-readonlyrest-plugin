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
import com.google.common.collect.Maps;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.settings.AuthMethodCreatorsRegistry;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class __old_UserSettingsCollection {

  public static final String ATTRIBUTE_NAME = "users";

  private final Map<String, __old_UserSettings> usersSettingsMap;

  private __old_UserSettingsCollection(List<__old_UserSettings> userSettings) {
    this.usersSettingsMap = Maps.newLinkedHashMap();
    userSettings.forEach(x -> this.usersSettingsMap.put(x.getUsername(), x));
  }

  @SuppressWarnings("unchecked")
  public static __old_UserSettingsCollection from(RawSettings data, AuthMethodCreatorsRegistry registry) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
      .map(list ->
             list.stream()
               .map(l -> new __old_UserSettings(new RawSettings((Map<String, ?>) l, data.getLogger()), registry))
               .collect(Collectors.toList())
      )
      .map(__old_UserSettingsCollection::new)
      .orElse(new __old_UserSettingsCollection(Lists.newArrayList()));
  }

  public __old_UserSettings get(String name) {
    if (!usersSettingsMap.containsKey(name))
      throw new SettingsMalformedException("Cannot find User definition with name '" + name + "'");
    return usersSettingsMap.get(name);
  }

  public List<__old_UserSettings> getAll() {
    return Lists.newArrayList(usersSettingsMap.values());
  }
}
