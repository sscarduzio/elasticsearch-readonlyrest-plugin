/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Lists;
import com.google.common.net.InternetDomainName;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.SecurityPermissionException;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.IPMask;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.rest.RestRequest;

import java.net.UnknownHostException;
import java.util.List;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class HostsRule extends Rule {

  private List<String> allowedAddresses;
  private Boolean acceptXForwardedForHeader;

  public HostsRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    acceptXForwardedForHeader = s.getAsBoolean("accept_x-forwarded-for_header", false);
    String[] a = s.getAsArray("hosts");
    if (a != null && a.length > 0) {
      allowedAddresses = Lists.newArrayList();
      for (int i = 0; i < a.length; i++) {
        if (!Strings.isNullOrEmpty(a[i])) {
          try {
            IPMask.getIPMask(a[i]);
          } catch (Exception e) {
            if (!InternetDomainName.isValid(a[i])) {
              throw new RuleConfigurationError("invalid address", e);
            }
          }
          allowedAddresses.add(a[i].trim());
        }
      }
    } else {
      throw new RuleNotConfiguredException();
    }
  }

  private static String getXForwardedForHeader(RestRequest request) {
    if (!Strings.isNullOrEmpty(request.header("X-Forwarded-For"))) {
      String[] parts = request.header("X-Forwarded-For").split(",");
      if (!Strings.isNullOrEmpty(parts[0])) {
        return parts[0];
      }
    }
    return null;
  }


  /*
   * All "matches" methods should return true if no explicit condition was configured
   */

  private boolean matchesAddress(String address, String xForwardedForHeader) {

    if (address == null) {
      throw new SecurityPermissionException("For some reason the origin address of this call could not be determined. Abort!", null);
    }
    if (allowedAddresses == null) {
      return true;
    }

    if (acceptXForwardedForHeader && xForwardedForHeader != null) {
      // Give it a try with the header
      return matchesAddress(xForwardedForHeader, null);
    }
    for (String allowedAddress : allowedAddresses) {
      if (allowedAddress.indexOf("/") > 0) {
        try {
          IPMask ipmask = IPMask.getIPMask(allowedAddress);
          if (ipmask.matches(address)) {
            return true;
          }
        } catch (UnknownHostException e) {
        }
      }
      if (allowedAddress.equals(address)) {
        return true;
      }
    }
    return false;
  }

  public RuleExitResult match(RequestContext rc) {
    boolean res = matchesAddress(rc.getRemoteAddress(), getXForwardedForHeader(rc.getRequest()));
    return res ? MATCH : NO_MATCH;
  }
}
