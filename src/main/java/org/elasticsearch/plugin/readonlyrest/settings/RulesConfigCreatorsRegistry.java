package org.elasticsearch.plugin.readonlyrest.settings;

import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthRuleSettings;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class RulesConfigCreatorsRegistry {

  private final Map<String, Supplier<RuleSettings>> ruleSettingsCreators;

  public RulesConfigCreatorsRegistry(RawSettings blockSettings,
                                     LdapsSettings ldapsSettings) {
    Map<String, Supplier<RuleSettings>> creators = new HashMap<>();
    creators.put(LdapAuthRuleSettings.ATTRIBUTE_NAME,
        () -> LdapAuthRuleSettings.from(blockSettings.inner(LdapAuthRuleSettings.ATTRIBUTE_NAME), ldapsSettings)
    );
    this.ruleSettingsCreators = creators;
  }

  public RuleSettings create(String name) {
    if (!ruleSettingsCreators.containsKey(name)) {
      throw new ConfigMalformedException("Unknown rule name: '" + name + "'");
    }
    return ruleSettingsCreators.get(name).get();
  }
}
