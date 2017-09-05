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

import org.elasticsearch.plugin.readonlyrest.acl.domain.KibanaAccess;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;

import java.util.function.Function;

public class KibanaAccessRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "kibana_access";
  public static final String ATTRIBUTE_KIBANA_INDEX = "kibana_index";

  private static final String DEFAULT_KIBANA_INDEX = ".kibana";

  private final KibanaAccess kibanaAccess;
  private final Value<String> kibanaIndex;

  public KibanaAccessRuleSettings(KibanaAccess kibanaAccess, String kibanaIndex) {
    this.kibanaAccess = kibanaAccess;
    this.kibanaIndex = Value.fromString(kibanaIndex, Function.identity());
  }

  public static KibanaAccessRuleSettings fromBlockSettings(RawSettings blockSettings) {
    String value = blockSettings.stringReq(ATTRIBUTE_NAME);
    return new KibanaAccessRuleSettings(
      KibanaAccess.fromString(value)
        .orElseThrow(() -> new SettingsMalformedException("Unknown kibana_access value: " + value)),
      blockSettings.stringOpt(ATTRIBUTE_KIBANA_INDEX).orElse(DEFAULT_KIBANA_INDEX)
    );
  }

  public KibanaAccess getKibanaAccess() {
    return kibanaAccess;
  }

  public Value<String> getKibanaIndex() {
    return kibanaIndex;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
