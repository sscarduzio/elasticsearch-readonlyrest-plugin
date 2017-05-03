package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

public class SearchlogRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "searchlog";

  private final boolean enabled;

  public static SearchlogRuleSettings from(boolean value) {
    return new SearchlogRuleSettings(value);
  }

  private SearchlogRuleSettings(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
