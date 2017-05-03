package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.KibanaAccess;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

public class KibanaAccessRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "kibana_access";
  public static final String ATTRIBUTE_KIBANA_INDEX = "kibana_index";

  private static final String DEFAULT_KIBANA_INDEX = ".kibana";

  private final KibanaAccess kibanaAccess;
  private final String kibanaIndex;

  public static KibanaAccessRuleSettings fromBlockSettings(RawSettings blockSettings) {
    String value = blockSettings.stringReq(ATTRIBUTE_NAME);
    return new KibanaAccessRuleSettings(
        KibanaAccess.fromString(value)
            .orElseThrow(() -> new ConfigMalformedException("Unknown kibana_access value: " + value)),
        blockSettings.stringOpt(ATTRIBUTE_KIBANA_INDEX).orElse(DEFAULT_KIBANA_INDEX)
    );
  }

  private KibanaAccessRuleSettings(KibanaAccess kibanaAccess, String kibanaIndex) {
    this.kibanaAccess = kibanaAccess;
    this.kibanaIndex = kibanaIndex;
  }

  public KibanaAccess getKibanaAccess() {
    return kibanaAccess;
  }

  public String getKibanaIndex() {
    return kibanaIndex;
  }
}
