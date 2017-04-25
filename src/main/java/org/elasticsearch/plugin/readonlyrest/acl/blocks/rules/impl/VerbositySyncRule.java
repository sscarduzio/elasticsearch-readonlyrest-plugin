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

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.Verbosity;

import java.util.Optional;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class VerbositySyncRule extends SyncRule {

  private static final Logger logger = Loggers.getLogger(VerbositySyncRule.class);

  protected Verbosity level;

  public VerbositySyncRule(Settings s) throws RuleNotConfiguredException, RuleConfigurationError {
    super();
    String tmp = s.get(getKey(), "info");
    try {
      level = Verbosity.valueOf(tmp.toUpperCase());
    } catch (Exception e) {
      throw new RuleConfigurationError(
        tmp + " is not a valid verbosity level. Try one of " +
          Joiner.on(",").join(
            Lists.newArrayList(Verbosity.ERROR, Verbosity.INFO)
          ),
        e
      );
    }
  }

  public static Optional<VerbositySyncRule> fromSettings(Settings s) {
    try {
      return Optional.of(new VerbositySyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
      return Optional.empty();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    rc.setVerbosity(level);
    return MATCH;
  }
}
