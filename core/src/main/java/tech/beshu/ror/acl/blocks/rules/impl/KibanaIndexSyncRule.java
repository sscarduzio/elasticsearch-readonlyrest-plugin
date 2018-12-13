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

package tech.beshu.ror.acl.blocks.rules.impl;

import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.commons.domain.__old_Value;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.RuleSettings;

import java.util.function.Function;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class KibanaIndexSyncRule extends SyncRule {
  private final KibanaIndexSyncRule.Settings settings;

  public KibanaIndexSyncRule(KibanaIndexSyncRule.Settings s) {
    this.settings = s;
  }

  @Override
  public RuleExitResult match(__old_RequestContext rc) {
    rc.setKibanaIndex(settings.kibanaIndex.getValue(rc).orElse(null));
    return MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  /**
   * Settings
   */
  public static class Settings implements RuleSettings {
    public static final String ATTRIBUTE_NAME = "kibana_index";
    private final __old_Value<String> kibanaIndex;

    public Settings(String kibanaIndexTpl) {
      this.kibanaIndex = __old_Value.fromString(kibanaIndexTpl, Function.identity());
    }

    public static Settings fromBlockSettings(RawSettings blockSettings) {
      return new Settings(blockSettings.stringReq(ATTRIBUTE_NAME));
    }

    @Override
    public String getName() {
      return ATTRIBUTE_NAME;
    }

  }
}
