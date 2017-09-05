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

import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.settings.AuthMethodCreatorsRegistry;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jooq.lambda.Seq.seq;

public class UserSettingsCollection {

  public static final String ATTRIBUTE_NAME = "users";

  private final Map<String, UserSettings> usersSettingsMap;

  private UserSettingsCollection(List<UserSettings> userSettings) {
    this.usersSettingsMap = seq(userSettings).toMap(UserSettings::getUsername, Function.identity());
  }

  @SuppressWarnings("unchecked")
  public static UserSettingsCollection from(RawSettings data, AuthMethodCreatorsRegistry registry) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
      .map(list ->
             list.stream()
               .map(l -> new UserSettings(new RawSettings((Map<String, ?>) l), registry))
               .collect(Collectors.toList())
      )
      .map(UserSettingsCollection::new)
      .orElse(new UserSettingsCollection(Lists.newArrayList()));
  }

  public UserSettings get(String name) {
    if (!usersSettingsMap.containsKey(name))
      throw new SettingsMalformedException("Cannot find User definition with name '" + name + "'");
    return usersSettingsMap.get(name);
  }

  public List<UserSettings> getAll() {
    return Lists.newArrayList(usersSettingsMap.values());
  }
}
