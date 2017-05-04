package org.elasticsearch.plugin.readonlyrest.settings.rules;

public class AuthKeyPlainTextRuleSettings extends AuthKeyRuleSettings {

  public static final String ATTRIBUTE_NAME = "auth_key";

  public static AuthKeyPlainTextRuleSettings from(String authKey) {
    return new AuthKeyPlainTextRuleSettings(authKey);
  }

  public AuthKeyPlainTextRuleSettings(String authKey) {
    super(authKey);
  }
}
