package org.elasticsearch.plugin.readonlyrest.settings.rules;

import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserSettingsCollection;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class GroupsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "groups";

  private final List<UserSettings> userWithGivenGroups;

  public static GroupsRuleSettings from(Set<String> groups, UserSettingsCollection userSettingsCollection) {
    return new GroupsRuleSettings(
        userSettingsCollection.getAll().stream()
            .filter(u -> !Sets.intersection(groups, u.getGroups()).isEmpty())
            .collect(Collectors.toList())
    );
  }

  private GroupsRuleSettings(List<UserSettings> userWithGivenGroups) {
    this.userWithGivenGroups = userWithGivenGroups;
  }

  public List<UserSettings> getUserWithGivenGroups() {
    return userWithGivenGroups;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
