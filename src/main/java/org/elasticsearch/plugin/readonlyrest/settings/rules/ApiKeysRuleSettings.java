package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.Set;

public class ApiKeysRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "api_keys";

  private final Set<String> apiKeys;

  public static ApiKeysRuleSettings from(Set<String> indices) {
    return new ApiKeysRuleSettings(indices);
  }

  public ApiKeysRuleSettings(Set<String> apiKeys) {
    this.apiKeys = apiKeys;
  }

  public Set<String> getApiKeys() {
    return apiKeys;
  }
}
