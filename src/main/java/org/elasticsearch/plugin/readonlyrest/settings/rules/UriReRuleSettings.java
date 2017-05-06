package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class UriReRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "uri_re";

  private final Value<Pattern> pattern;

  public static UriReRuleSettings from(String value) {
    return new UriReRuleSettings(Value.fromString(value, patternFromString));
  }

  private UriReRuleSettings(Value<Pattern> pattern) {
    this.pattern = pattern;
  }

  public Value<Pattern> getPattern() {
    return pattern;
  }

  private static Function<String, Pattern> patternFromString = value -> {
    try {
      return Pattern.compile(value);
    } catch (PatternSyntaxException e) {
      throw new ConfigMalformedException("invalid 'uri_re' regexp", e);
    }
  };
}

