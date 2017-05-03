package org.elasticsearch.plugin.readonlyrest.settings.definitions;

import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.settings.AuthMethodCreatorsRegistry;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jooq.lambda.Seq.seq;

public class UserSettingsCollection {

  private static final String ATTRIBUTE_NAME = "users";

  private final Map<String, UserSettings> usersSettingsMap;

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

  private UserSettingsCollection(List<UserSettings> userSettings) {
    this.usersSettingsMap = seq(userSettings).toMap(UserSettings::getUsername, Function.identity());
  }

  public UserSettings get(String name) {
    if (!usersSettingsMap.containsKey(name))
      throw new ConfigMalformedException("Cannot find User definition with name '" + name + "'");
    return usersSettingsMap.get(name);
  }

  public List<UserSettings> getAll() {
    return Lists.newArrayList(usersSettingsMap.values());
  }
}
