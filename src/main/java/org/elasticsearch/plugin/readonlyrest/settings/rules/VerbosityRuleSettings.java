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

import org.elasticsearch.plugin.readonlyrest.acl.domain.Verbosity;
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

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}