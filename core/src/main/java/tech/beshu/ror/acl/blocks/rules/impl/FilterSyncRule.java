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
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.commons.domain.Value;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.utils.FilterTransient;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.RuleSettings;

import java.util.function.Function;

/**
 * Document level security (DLS) rule.
 * When applied, it forwards the filter query to @{@link tech.beshu.ror.es.security.DocumentFieldReader} via a context header in @{@link __old_RequestContext}.
 * Created by sscarduzio on 31/05/2018.
 */
public class FilterSyncRule extends SyncRule {
  private final FilterSyncRule.Settings settings;

  public FilterSyncRule(FilterSyncRule.Settings s) {
    this.settings = s;
  }

  @Override
  public RuleExitResult match(__old_RequestContext rc) {
    if (!rc.isReadRequest()) {
      return NO_MATCH;
    }

    // [DLS] forwarding constraint to next stage
    String filterWithResolvedVars = Value.fromString(settings.filter, Function.identity()).getValue(rc).get();
    rc.setContextHeader(Constants.FILTER_TRANSIENT, FilterTransient.createFromFilter(filterWithResolvedVars).serialize());

    return MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  /**
   * Settings for FLS
   */
  public static class Settings implements RuleSettings {
    public static final String ATTRIBUTE_NAME = "filter";
    private final String filter;

    public Settings(String filter) {
      this.filter = filter;
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
