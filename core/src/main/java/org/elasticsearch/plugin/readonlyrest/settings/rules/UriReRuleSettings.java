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

import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;

import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class UriReRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "uri_re";
  private static Function<String, Pattern> patternFromString = value -> {
    try {
      return Pattern.compile(value);
    } catch (PatternSyntaxException e) {
      throw new SettingsMalformedException("invalid 'uri_re' regexp", e);
    }
  };
  private final Value<Pattern> pattern;

  private UriReRuleSettings(Value<Pattern> pattern) {
    this.pattern = pattern;
  }

  public static UriReRuleSettings from(String value) {
    return new UriReRuleSettings(Value.fromString(value, patternFromString));
  }

  public Value<Pattern> getPattern() {
    return pattern;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}

