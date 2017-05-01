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

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class UriReSyncRule extends SyncRule {

  private Pattern uri_re = null;

  public UriReSyncRule(Settings s) throws RuleNotConfiguredException {
    super();

    String tmp = s.get(getKey());
    if (!Strings.isNullOrEmpty(tmp)) {
      try {
        uri_re = Pattern.compile(tmp.trim());
      } catch (PatternSyntaxException e) {
        throw new RuleConfigurationError("invalid 'uri_re' regexp", e);
      }

    }
    else {
      throw new RuleNotConfiguredException();
    }
  }

  public static Optional<UriReSyncRule> fromSettings(Settings s) {
    try {
      return Optional.of(new UriReSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
      return Optional.empty();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    if (uri_re == null) {
      return NO_MATCH;
    }
    return uri_re.matcher(rc.getUri()).find() ? MATCH : NO_MATCH;
  }
}
