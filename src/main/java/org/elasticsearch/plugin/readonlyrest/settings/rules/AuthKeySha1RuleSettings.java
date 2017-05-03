package org.elasticsearch.plugin.readonlyrest.settings.rules;

public class AuthKeySha1RuleSettings extends AuthKeyRuleSettings {

  public static final String ATTRIBUTE_NAME = "auth_key_sha1";

  public static AuthKeySha1RuleSettings from(String authKey) {
    return new AuthKeySha1RuleSettings(authKey);
  }

  private AuthKeySha1RuleSettings(String authKey) {
    super(authKey);
  }
}
