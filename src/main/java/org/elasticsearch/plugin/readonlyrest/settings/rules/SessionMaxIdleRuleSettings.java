package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.time.Duration;

public class SessionMaxIdleRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "session_max_idle";

  private final Duration maxIdle;

  public static SessionMaxIdleRuleSettings from(long millis) {
    return new SessionMaxIdleRuleSettings(Duration.ofMillis(millis));
  }

  private SessionMaxIdleRuleSettings(Duration maxIdle) {
    this.maxIdle = maxIdle;
  }

  public Duration getMaxIdle() {
    return maxIdle;
  }
}
