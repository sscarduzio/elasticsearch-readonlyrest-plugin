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

package tech.beshu.ror.commons.utils;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import tech.beshu.ror.commons.settings.SettingsMalformedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public class FieldLevelSecuritySettingsTransient {
  private static Gson gson = new Gson();
  private final ImmutableSet<String> flsFields;
  private boolean isBlackList = false;

  FieldLevelSecuritySettingsTransient(Optional<Set<String>> fields) {
    assert fields != null;
    if (fields == null) {
      flsFields = ImmutableSet.of();
      return;
    }
    Set<String> tmpFields = Sets.newHashSet();
    fields.ifPresent(fs -> {
      for (String field : fs) {
        boolean isBL = field.startsWith("!") || field.startsWith("~");
        if (isBlackList && isBL != isBlackList) {
          throw new SettingsMalformedException("can't mix black list with white list in field level security");
        }
        isBlackList = isBL;
        tmpFields.add(field);
      }
    });
    flsFields = ImmutableSet.copyOf(tmpFields);
  }

  public ImmutableSet<String> getFlsFields() {
    return flsFields;
  }

  public boolean isBlackList() {
    return isBlackList;
  }

  public String serialize(){
    return gson.toJson(getFlsFields().asList());
  }
  static Optional<FieldLevelSecuritySettingsTransient> deserialize(String s){
    Set<String> fields = gson.fromJson(s,new TypeToken<Set<String>>(){}.getType());
    return Optional.ofNullable(fields).map(set -> new FieldLevelSecuritySettingsTransient(Optional.ofNullable(set)));
  }
}

