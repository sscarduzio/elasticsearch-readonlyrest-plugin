package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.Set;

public class KibanaHideAppsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "x-kibana-hide-apps";

  private final Set<String> kibanaHideApps;

  public static KibanaHideAppsRuleSettings from(Set<String> kibanaHideApps) {
    return new KibanaHideAppsRuleSettings(kibanaHideApps);
  }

  private KibanaHideAppsRuleSettings(Set<String> kibanaHideApps) {
    this.kibanaHideApps = kibanaHideApps;
  }

  public Set<String> getKibanaHideApps() {
    return kibanaHideApps;
  }
}
