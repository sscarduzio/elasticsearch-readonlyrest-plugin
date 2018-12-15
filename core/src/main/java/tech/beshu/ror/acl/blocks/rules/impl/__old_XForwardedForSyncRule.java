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
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.__old_XForwardedForRuleSettings;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class __old_XForwardedForSyncRule extends SyncRule {

  private final __old_XForwardedForRuleSettings settings;

  public __old_XForwardedForSyncRule(__old_XForwardedForRuleSettings s) {
    this.settings = s;
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

    // Handle header as anonimised identifier
    if (settings.getAllowedIdentifiers().stream()
      .anyMatch(v -> v.getValue(rc).filter(s -> s.equals(header)).isPresent())) {
      return MATCH;
    }

    Inet4Address resolved = null;
    try {
      resolved = (Inet4Address) InetAddress.getByName(header);
      if (resolved == null) {
        return NO_MATCH;
      }
    } catch (UnknownHostException e) {
      // This string cannot be resolved, useless to try match with numeric
      return NO_MATCH;
    }


    // Handle header as IP
    Inet4Address finalResolved = resolved;
    boolean res = settings.getAllowedNumeric().stream()
      .anyMatch(ip -> {
        // This resolves names if necessary
        return ip.matches(finalResolved);
      });


    return res ? MATCH : NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

}