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
import tech.beshu.ror.commons.utils.MatcherWithWildcards;
import tech.beshu.ror.requestcontext.__old_RequestContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by sscarduzio on 14/02/2016.
 * This is a clone of headers rule (now deprecated), as it also evaluates arguments in logical AND
 */
public class __old_HeadersOrSyncRule extends __old_HeadersSyncRule {

  public __old_HeadersOrSyncRule(Settings s) {
    super(s);
  }

  /**
   * We match headers in a way that the header name is case insensitive, and the header value is case sensitive
   * This is an OR evaluated variant of {@link __old_HeadersAndSyncRule}
   *
   * @param rc the __old_RequestContext
   * @return match or no match
   */
  @Override
  public RuleExitResult match(__old_RequestContext rc) {

    Map<String, String> subsetHeaders = new HashMap<>(allowedHeaders.size());
    for (Map.Entry<String, String> kv : rc.getHeaders().entrySet()) {
      String lowerCaseKey = kv.getKey().toLowerCase();
      if (allowedHeaders.contains(lowerCaseKey)) {
        subsetHeaders.put(lowerCaseKey, kv.getValue());
      }
    }

    // First check that we have some of the required headers
    if (subsetHeaders.size() == 0) {
      return NO_MATCH;
    }

    Set<String> flattenedActualHeaders = subsetHeaders
        .entrySet()
        .stream()
        .map(kv -> kv.getKey().toLowerCase() + ":" + kv.getValue())
        .collect(Collectors.toSet());

    return new MatcherWithWildcards(settings.getFlatHeaders())
        .filter(flattenedActualHeaders)
        .size() > 0 ? MATCH : NO_MATCH;

  }

  @Override
  public String getKey() {
    return super.settings.getName();
  }

  public static class Settings extends __old_HeadersSyncRule.Settings {
    public static final String ATTRIBUTE_NAME = "headers_or";

    public Settings(Set<String> headersWithValue) {
      super(headersWithValue);
    }

    @Override
    protected boolean shouldRejectDuplicateHeaderKey() {
      return false;
    }

    @Override
    public String getName() {
      return ATTRIBUTE_NAME;
    }

  }
}
