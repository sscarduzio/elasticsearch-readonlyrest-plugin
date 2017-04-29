package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ProxyAuthConfigSettingsCollection;

import java.util.List;

public class ProxyAuthRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "proxy_auth";

  private static final String PROXY_AUTH_CONFIG = "proxy_auth_config";
  private static final String USERS = "users";

  private static final String DEFAULT_HEADER_NAME = "X-Forwarded-User";

  private final List<String> users;
  private final String userIdHeader;

  @SuppressWarnings("unchecked")
  public static ProxyAuthRuleSettings from(RawSettings settings,
                                           ProxyAuthConfigSettingsCollection proxyAuthConfigSettingsCollection) {
    String providerName = settings.stringReq(PROXY_AUTH_CONFIG);
    return new ProxyAuthRuleSettings(
        (List<String>) settings.notEmptyListReq(USERS),
        proxyAuthConfigSettingsCollection.get(providerName).getUserIdHeader()
    );
  }

  public static ProxyAuthRuleSettings from(List<String> users) {
    return new ProxyAuthRuleSettings(users, DEFAULT_HEADER_NAME);
  }

  private ProxyAuthRuleSettings(List<String> users, String userIdHeader) {
    this.users = users;
    this.userIdHeader = userIdHeader;
  }

  public List<String> getUsers() {
    return users;
  }

  public String getUserIdHeader() {
    return userIdHeader;
  }
}
