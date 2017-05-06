package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserSettingsCollection;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GroupsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "groups";

  private final List<UserSettings> usersSettings;
  private final Set<Value<String>> groups;

  public static GroupsRuleSettings from(Set<String> groups, UserSettingsCollection userSettingsCollection) {
    return new GroupsRuleSettings(groups, userSettingsCollection.getAll());
  }

  private GroupsRuleSettings(Set<String> groups, List<UserSettings> usersSettings) {
    this.usersSettings = usersSettings;
    this.groups = groups.stream().map(g -> Value.fromString(g, Function.identity())).collect(Collectors.toSet());
  }

  public List<UserSettings> getUsersSettings() {
    return usersSettings;
  }

  public Set<Value<String>> getGroups() {
    return groups;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
