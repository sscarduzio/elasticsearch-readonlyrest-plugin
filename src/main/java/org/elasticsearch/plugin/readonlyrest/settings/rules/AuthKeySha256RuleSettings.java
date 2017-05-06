package org.elasticsearch.plugin.readonlyrest.settings.rules;

public class AuthKeySha256RuleSettings extends AuthKeyRuleSettings {

  public static final String ATTRIBUTE_NAME = "auth_key_sha256";

  public static AuthKeySha256RuleSettings from(String authKey) {
    return new AuthKeySha256RuleSettings(authKey);
  }

  public AuthKeySha256RuleSettings(String authKey) {
    super(authKey);
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
