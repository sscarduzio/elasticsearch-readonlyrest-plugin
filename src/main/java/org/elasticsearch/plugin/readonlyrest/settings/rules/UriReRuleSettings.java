package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class UriReRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "uri_re";

  private final Pattern pattern;

  public static UriReRuleSettings from(String value) {
    try {
      return new UriReRuleSettings(Pattern.compile(value));
    } catch (PatternSyntaxException e) {
      throw new RuleConfigurationError("invalid 'uri_re' regexp", e);
    }
  }

  private UriReRuleSettings(Pattern pattern) {
    this.pattern = pattern;
  }

  public Pattern getPattern() {
    return pattern;
  }
}

