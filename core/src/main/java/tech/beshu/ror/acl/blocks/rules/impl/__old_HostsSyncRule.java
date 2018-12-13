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
import tech.beshu.ror.commons.domain.IPMask;
import tech.beshu.ror.commons.domain.__old_Value;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.HostsRuleSettings;
import tech.beshu.ror.settings.rules.XForwardedForRuleSettings;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class __old_HostsSyncRule extends SyncRule {

  private final HostsRuleSettings settings;
  private final LoggerShim logger;
  private final Set<__old_Value<String>> allowedAddressesStrings;
  private final ESContext context;
  private final Boolean acceptXForwardedForHeader;

  public __old_HostsSyncRule(HostsRuleSettings s, ESContext context) {
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

  public RuleExitResult match(__old_RequestContext rc) {
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
  private boolean matchesAddress(__old_RequestContext rc, String address, String xForwardedForHeader) {
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

  private boolean ipMatchesAddress(String allowedHost, String address) {
    try {
      String allowedResolvedIp = allowedHost;

      if (!XForwardedForRuleSettings.isInetAddressOrBlock(allowedHost)) {
        // Super-late DNS resolution
        allowedResolvedIp = InetAddress.getByName(allowedHost).getHostAddress();
      }

      IPMask ip = IPMask.getIPMask(allowedResolvedIp);
      return ip.matches(address);
    } catch (UnknownHostException e) {
      logger.warn("Cannot resolve configured host name! " + e.getClass().getSimpleName() + ": " + allowedHost);
      return false;
    }
  }

}
