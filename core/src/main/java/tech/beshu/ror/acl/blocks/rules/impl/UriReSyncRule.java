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
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.RuleSettings;

import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class UriReSyncRule extends SyncRule {

  private final __old_Value<Pattern> uri_re;
  private final Settings settings;

  public UriReSyncRule(Settings s) {
    this.uri_re = s.getPattern();
    this.settings = s;
  }

  @Override
  public RuleExitResult match(__old_RequestContext rc) {
    return uri_re.getValue(rc)
                 .map(re -> re.matcher(rc.getUri()).find() ? MATCH : NO_MATCH)
                 .orElse(NO_MATCH);
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  public static class Settings implements RuleSettings {

    public static final String ATTRIBUTE_NAME = "uri_re";
    private static Function<String, Pattern> patternFromString = value -> {
      try {
        return Pattern.compile(value);
      } catch (PatternSyntaxException e) {
        throw new SettingsMalformedException("invalid 'uri_re' regexp", e);
      }
    };
    private final __old_Value<Pattern> pattern;

    private Settings(__old_Value<Pattern> pattern) {
      this.pattern = pattern;
    }

    public static UriReSyncRule.Settings from(String value) {
      return new Settings(__old_Value.fromString(value, patternFromString));
    }

    public __old_Value<Pattern> getPattern() {
      return pattern;
    }

    @Override
    public String getName() {
      return ATTRIBUTE_NAME;
    }
  }
}
