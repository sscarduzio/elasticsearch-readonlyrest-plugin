package org.elasticsearch.plugin.readonlyrest.settings;

import com.google.common.collect.Maps;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ProxyAuthConfigSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeyPlainTextRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeySha1RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeySha256RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ProxyAuthRuleSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class AuthMethodCreatorsRegistry {

  private final Map<String, Function<RawSettings, AuthKeyProvider>> authKeyProviderCreators;

  AuthMethodCreatorsRegistry(ProxyAuthConfigSettingsCollection proxyAuthConfigSettingsCollection) {
    HashMap<String, Function<RawSettings, AuthKeyProvider>> creators = Maps.newHashMap();
    creators.put(AuthKeyPlainTextRuleSettings.ATTRIBUTE_NAME, authKeySettingsCreator());
    creators.put(AuthKeySha1RuleSettings.ATTRIBUTE_NAME, authKeySha1SettingsCreator());
    creators.put(AuthKeySha256RuleSettings.ATTRIBUTE_NAME, authKeySha256SettingsCreator());
    creators.put(ProxyAuthRuleSettings.ATTRIBUTE_NAME, proxyAuthSettingsCreator(proxyAuthConfigSettingsCollection));
    this.authKeyProviderCreators = creators;
  }

  public AuthKeyProvider create(String name, RawSettings settings) {
    if (!authKeyProviderCreators.containsKey(name)) {
      throw new ConfigMalformedException("Unknown auth method name: '" + name + "'");
    }
    return authKeyProviderCreators.get(name).apply(settings);
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProvider> authKeySettingsCreator() {
    return settings -> AuthKeyPlainTextRuleSettings.from(settings.stringReq(AuthKeyPlainTextRuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProvider> authKeySha1SettingsCreator() {
    return settings -> AuthKeySha1RuleSettings.from(settings.stringReq(AuthKeySha1RuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProvider> authKeySha256SettingsCreator() {
    return settings -> AuthKeySha256RuleSettings.from(settings.stringReq(AuthKeySha256RuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProvider> proxyAuthSettingsCreator(ProxyAuthConfigSettingsCollection proxyAuthConfigSettingsCollection) {
    return settings -> {
      Object s = settings.req(ProxyAuthRuleSettings.ATTRIBUTE_NAME);
      return s instanceof List<?>
          ? ProxyAuthRuleSettings.from((List<String>) s)
          : ProxyAuthRuleSettings.from(new RawSettings((Map<String, ?>) s), proxyAuthConfigSettingsCollection);
    };
  }

}
