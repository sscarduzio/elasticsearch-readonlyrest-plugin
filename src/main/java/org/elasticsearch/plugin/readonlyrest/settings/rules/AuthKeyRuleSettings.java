package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.AuthKeyProvider;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

public abstract class AuthKeyRuleSettings implements RuleSettings, AuthKeyProvider {

  private final String authKey;

  protected AuthKeyRuleSettings(String authKey) {
    this.authKey = authKey;
  }

  public String getAuthKey() {
    return authKey;
  }
}
