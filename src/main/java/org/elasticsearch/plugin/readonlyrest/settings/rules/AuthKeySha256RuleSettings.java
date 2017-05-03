package org.elasticsearch.plugin.readonlyrest.settings.rules;

public class AuthKeySha256RuleSettings extends AuthKeyRuleSettings {

  public static final String ATTRIBUTE_NAME = "auth_key_sha256";

  public static AuthKeySha256RuleSettings from(String authKey) {
    return new AuthKeySha256RuleSettings(authKey);
  }

  private AuthKeySha256RuleSettings(String authKey) {
    super(authKey);
  }
}
