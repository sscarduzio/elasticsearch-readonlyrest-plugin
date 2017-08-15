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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

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
  public RuleExitResult match(RequestContext rc) {
    rc.getKibanaIndices().add(settings.kibanaIndex.getValue(rc).orElse(".kibana"));
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
    private final Value<String> kibanaIndex;

    public Settings(String kibanaIndexTpl) {
      this.kibanaIndex = Value.fromString(kibanaIndexTpl, Function.identity());
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
