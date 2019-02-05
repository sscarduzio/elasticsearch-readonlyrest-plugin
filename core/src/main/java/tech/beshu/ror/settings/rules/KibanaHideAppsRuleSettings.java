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
package tech.beshu.ror.settings.rules;

import tech.beshu.ror.settings.RuleSettings;

import java.util.Set;

public class KibanaHideAppsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "kibana_hide_apps";

  private final Set<String> kibanaHideApps;

  private KibanaHideAppsRuleSettings(Set<String> kibanaHideApps) {
    this.kibanaHideApps = kibanaHideApps;
  }

  public static KibanaHideAppsRuleSettings from(Set<String> kibanaHideApps) {
    return new KibanaHideAppsRuleSettings(kibanaHideApps);
  }

  public Set<String> getKibanaHideApps() {
    return kibanaHideApps;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
