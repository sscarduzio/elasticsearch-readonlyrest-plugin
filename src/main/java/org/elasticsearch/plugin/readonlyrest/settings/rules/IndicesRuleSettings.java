package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.Set;

public class IndicesRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "indices";

  private final Set<String> indices;

  public static IndicesRuleSettings from(Set<String> indices) {
    return new IndicesRuleSettings(indices);
  }

  private IndicesRuleSettings(Set<String> indices) {
    this.indices = indices;
  }

  public Set<String> getIndices() {
    return indices;
  }
}
