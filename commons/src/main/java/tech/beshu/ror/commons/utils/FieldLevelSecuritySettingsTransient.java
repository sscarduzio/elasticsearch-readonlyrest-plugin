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

