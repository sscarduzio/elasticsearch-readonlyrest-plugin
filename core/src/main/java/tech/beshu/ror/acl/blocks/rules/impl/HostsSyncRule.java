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
import tech.beshu.ror.commons.shims.ESContext;
import tech.beshu.ror.commons.shims.LoggerShim;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.acl.domain.IPMask;
import tech.beshu.ror.acl.domain.Value;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.HostsRuleSettings;

import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class HostsSyncRule extends SyncRule {

  private final HostsRuleSettings settings;
  private final LoggerShim logger;
  private final Set<Value<String>> allowedAddressesStrings;
  private final ESContext context;
  private final Boolean acceptXForwardedForHeader;

  public HostsSyncRule(HostsRuleSettings s, ESContext context) {
    this.acceptXForwardedForHeader = s.isAcceptXForwardedForHeader();
    this.allowedAddressesStrings = s.getAllowedAddresses();

    this.context = context;
    this.logger = context.logger(getClass());
    this.settings = s;
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

  public RuleExitResult match(RequestContext rc) {
    boolean res = matchesAddress(rc, rc.getRemoteAddress(), getXForwardedForHeader(rc.getHeaders()));
    return res ? MATCH : NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  /*
   * All "matches" methods should return true if no explicit condition was configured
   */
  private boolean matchesAddress(RequestContext rc, String address, String xForwardedForHeader) {
    if (address == null) {
      throw context.rorException("For some reason the origin address of this call could not be determined. Abort!");
    }
    if (allowedAddressesStrings.isEmpty()) {
      return true;
    }

    if (acceptXForwardedForHeader && xForwardedForHeader != null) {
      // Give it a try with the header
      boolean attemptXFwdFor = matchesAddress(rc, xForwardedForHeader, null);
      if (attemptXFwdFor) {
        return true;
      }
    }

    return allowedAddressesStrings.stream()
      .anyMatch(value ->
                  value.getValue(rc)
                    .map(ip -> ipMatchesAddress(ip, address))
                    .orElse(false)
      );
  }

  private boolean ipMatchesAddress(String hostString, String address) {
    try {
      IPMask ip = IPMask.getIPMask(hostString);
      return ip.matches(address);
    } catch (UnknownHostException e) {
      logger.warn("Cannot resolve configured host name! " + e.getClass().getSimpleName() + ": " + hostString);
      return false;
    }
  }

}
