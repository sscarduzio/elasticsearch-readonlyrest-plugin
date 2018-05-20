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

import com.google.common.base.Joiner;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.commons.utils.MatcherWithWildcardsAndNegations;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.RuleSettings;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by sscarduzio on 18/05/2018.
 */
public class FieldsSyncRule extends SyncRule {
  private final FieldsSyncRule.Settings settings;
  private final String fieldsAsString;

  public FieldsSyncRule(FieldsSyncRule.Settings s) {
    this.settings = s;
    this.fieldsAsString = Joiner.on(",").join(settings.fields);
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    rc.setContextHeader(Constants.FIELDS_TRANSIENT, fieldsAsString);
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
    public static final String ATTRIBUTE_NAME = "fields";
    private final Set<String> fields;

    public Settings(Set<String> fields) {
      this.fields = fields;
      int negatedItems = fields.stream().filter(s -> s.startsWith("~")).collect(Collectors.toList()).size();
      if (negatedItems != 0 && negatedItems != fields.size()) {
        throw new SettingsMalformedException("fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: " + fields);
      }

      Set<String> fieldsWithnNormalizedNegations = fields.stream().map(f -> f.startsWith("~") ? f.substring(1, f.length()) : f).collect(Collectors.toSet());
      if (!new MatcherWithWildcardsAndNegations(fieldsWithnNormalizedNegations).filter(Constants.FIELDS_ALWAYS_ALLOW).isEmpty()) {
        throw new SettingsMalformedException("The fields rule cannot contain always-allowed fields: " + Constants.FIELDS_ALWAYS_ALLOW);
      }

      // Unless explicitly allowed, the _all meta-field shoudl be disallowed
      if (!fields.contains("_all")){
        fields.add("~_all");
      }
    }

    public static Settings fromBlockSettings(RawSettings blockSettings) {
      return new Settings((Set<String>) blockSettings.notEmptySetReq(ATTRIBUTE_NAME));
    }

    @Override
    public String getName() {
      return ATTRIBUTE_NAME;
    }

  }

  public static class FieldPolicy {
    private final MatcherWithWildcardsAndNegations fieldsMatcher;

    public FieldPolicy(Set<String> fields) {

      this.fieldsMatcher = new MatcherWithWildcardsAndNegations(fields);
    }

    public boolean canKeep(String field) {
      if (Constants.FIELDS_ALWAYS_ALLOW.contains(field)) {
        return true;
      }
      return fieldsMatcher.match(field);
    }

  }
}
