package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Verbosity;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

public class VerbosityRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "verbosity";

  private final Verbosity verbosity;

  public static VerbosityRuleSettings from(String verbosityStr) {
    return new VerbosityRuleSettings(Verbosity.fromString(verbosityStr)
        .orElseThrow(() -> new ConfigMalformedException("Unknown verbosity value: " + verbosityStr)));
  }

  private VerbosityRuleSettings(Verbosity verbosity) {
    this.verbosity = verbosity;
  }

  public Verbosity getVerbosity() {
    return verbosity;
  }
}