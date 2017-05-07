package org.elasticsearch.plugin.readonlyrest.settings.definitions;

import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jooq.lambda.Seq.seq;

public class UserGroupsProviderSettingsCollection {

  public static final String ATTRIBUTE_NAME = "user_groups_providers";

  private final Map<String, UserGroupsProviderSettings> userGroupsProviderSettingsMap;

  @SuppressWarnings("unchecked")
  public static UserGroupsProviderSettingsCollection from(RawSettings data) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
        .map(list ->
            list.stream()
                .map(l -> new UserGroupsProviderSettings(new RawSettings((Map<String, ?>) l)))
                .collect(Collectors.toList())
        )
        .map(UserGroupsProviderSettingsCollection::new)
        .orElse(new UserGroupsProviderSettingsCollection(Lists.newArrayList()));
  }

  private UserGroupsProviderSettingsCollection(List<UserGroupsProviderSettings> groupsProviderSettingsList) {
    validate(groupsProviderSettingsList);
    this.userGroupsProviderSettingsMap = seq(groupsProviderSettingsList).toMap(UserGroupsProviderSettings::getName, Function.identity());
  }

  public UserGroupsProviderSettings get(String name) {
    if (!userGroupsProviderSettingsMap.containsKey(name))
      throw new ConfigMalformedException("Cannot find User Groups Provider definition with name '" + name + "'");
    return userGroupsProviderSettingsMap.get(name);
  }

  private void validate(List<UserGroupsProviderSettings> grouspProviderSettings) {
    List<String> names = seq(grouspProviderSettings).map(UserGroupsProviderSettings::getName).collect(Collectors.toList());
    if(names.stream().distinct().count() != names.size()) {
      throw new ConfigMalformedException("Duplicated User Groups Provider name in '" + ATTRIBUTE_NAME + "' section");
    }
  }
}
