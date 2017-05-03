package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

public class MaxBodyLengthRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "max_body_length";

  private final Integer maxBodyLength;

  public static MaxBodyLengthRuleSettings from(Integer value) {
    return new MaxBodyLengthRuleSettings(value);
  }

  private MaxBodyLengthRuleSettings(Integer maxBodyLength) {
    this.maxBodyLength = maxBodyLength;
  }

  public Integer getMaxBodyLength() {
    return maxBodyLength;
  }
}
