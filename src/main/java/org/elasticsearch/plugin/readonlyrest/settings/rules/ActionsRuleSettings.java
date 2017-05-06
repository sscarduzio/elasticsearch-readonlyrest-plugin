package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.Set;

public class ActionsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "actions";

  private final Set<String> actions;

  public static ActionsRuleSettings from(Set<String> indices) {
    return new ActionsRuleSettings(indices);
  }

  private ActionsRuleSettings(Set<String> actions) {
    this.actions = actions;
  }

  public Set<String> getActions() {
    return actions;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
