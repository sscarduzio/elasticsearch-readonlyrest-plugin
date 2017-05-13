/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
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

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
