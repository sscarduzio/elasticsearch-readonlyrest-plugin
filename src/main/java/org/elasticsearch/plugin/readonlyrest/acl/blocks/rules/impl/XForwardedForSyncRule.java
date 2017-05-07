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

import com.google.common.base.Strings;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.acl.domain.IPMask;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.settings.rules.XForwardedForRuleSettings;

import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class XForwardedForSyncRule extends SyncRule {

  private final Set<Value<IPMask>> allowedAddresses;
  private final XForwardedForRuleSettings settings;

  public XForwardedForSyncRule(XForwardedForRuleSettings s) {
    this.allowedAddresses = s.getAllowedAddresses();
    this.settings = s;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    String header = getXForwardedForHeader(rc.getHeaders());

    if (header == null) {
      return NO_MATCH;
    }

    boolean res = matchesAddress(rc, header);
    return res ? MATCH : NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  /*
   * All "matches" methods should return true if no explicit condition was configured
   */
  private boolean matchesAddress(RequestContext rc, String address) {
    if (allowedAddresses == null) {
      return true;
    }

    return allowedAddresses.stream()
        .anyMatch(value ->
            value.getValue(rc)
                .map(ip -> ipMatchesAddress(ip, address))
                .orElse(false)
        );
  }

  private boolean ipMatchesAddress(IPMask ip, String address) {
    try {
      return ip.matches(address);
    } catch (UnknownHostException e) {
      return false;
    }
  }

  private static String getXForwardedForHeader(Map<String, String> headers) {
    String header = headers.get("X-Forwarded-For");
    if (!Strings.isNullOrEmpty(header)) {
      String[] parts = header.split(",");
      if (!Strings.isNullOrEmpty(parts[0])) {
        return parts[0];
      }
    }
    return null;
  }
}