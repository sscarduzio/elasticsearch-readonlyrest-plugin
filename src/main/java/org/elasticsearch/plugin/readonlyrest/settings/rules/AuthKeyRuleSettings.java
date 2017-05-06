package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.AuthKeyProviderSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

public abstract class AuthKeyRuleSettings implements AuthKeyProviderSettings {

  private final String authKey;

  protected AuthKeyRuleSettings(String authKey) {
    this.authKey = authKey;
  }

  public String getAuthKey() {
    return authKey;
  }
}
