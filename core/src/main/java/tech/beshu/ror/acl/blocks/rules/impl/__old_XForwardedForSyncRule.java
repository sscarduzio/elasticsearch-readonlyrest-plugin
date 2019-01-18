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

import com.google.common.base.Strings;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.acl.domain.__old_Value;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.__old_XForwardedForRuleSettings;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class __old_XForwardedForSyncRule extends SyncRule {

  private final Set<__old_Value<String>> allowedAddresses;
  private final __old_XForwardedForRuleSettings settings;
  private final LoggerShim logger;

  public __old_XForwardedForSyncRule(__old_XForwardedForRuleSettings s, ESContext context) {
    this.allowedAddresses = s.getAllowedIdentifiers();
    this.settings = s;
    this.logger = context.logger(this.getClass());
  }

  private static String getXForwardedForHeader(Map<String, String> headers) {
    String header = headers.get("X-Forwarded-For");
    if (!Strings.isNullOrEmpty(header)) {
      String[] parts = header.split(",");
      if (!Strings.isNullOrEmpty(parts[0])) {
        return parts[0].trim();
      }
    }
    return null;
  }

  @Override
  public RuleExitResult match(__old_RequestContext rc) {
    String header = getXForwardedForHeader(rc.getHeaders());

    // Handle unknown case
    if (header == null || "unknown".equals(header.toLowerCase())) {
      return NO_MATCH;
    }

    Set<String> allowedStrings = settings.getAllowedIdentifiers()
                                         .stream()
                                         .map(v -> v.getValue(rc))
                                         .filter(Optional::isPresent)
                                         .map(Optional::get)
                                         .collect(Collectors.toSet());

    if (allowedStrings
        .stream()
        .anyMatch(v -> v.equals(header))) {
      return MATCH;
    }

    return allowedStrings
        .stream()
        .filter(allowed -> __old_HostsSyncRule.ipMatchesAddress(allowed, header, logger))
        .findFirst()
        .isPresent() ? MATCH : NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

}