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
import com.google.common.collect.Lists;
import com.google.common.net.InternetDomainName;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.IPMask;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.requestcontext.RequestContext;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class XForwardedForSyncRule extends SyncRule {

  private List<String> allowedAddresses;

  private XForwardedForSyncRule(Settings s) throws RuleNotConfiguredException {
    super();
    String[] addressed = s.getAsArray(getKey());
    if (addressed != null && addressed.length > 0) {
      allowedAddresses = Lists.newArrayList();
      for (String address : addressed) {
        if (!Strings.isNullOrEmpty(address)) {
          try {
            IPMask.getIPMask(address);
          } catch (Exception e) {
            if (!InternetDomainName.isValid(address)) {
              throw new RuleConfigurationError("invalid address", e);
            }
          }
          allowedAddresses.add(address.trim());
        }
      }
    }
    else {
      throw new RuleNotConfiguredException();
    }
  }

  public static Optional<XForwardedForSyncRule> fromSettings(Settings s) {
    try {
      return Optional.of(new XForwardedForSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
      return Optional.empty();
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


  /*
   * All "matches" methods should return true if no explicit condition was configured
   */

  private boolean matchesAddress(String address) {
    if (allowedAddresses == null) {
      return true;
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
    String header = getXForwardedForHeader(rc.getHeaders());

    if (header == null) {
      return NO_MATCH;
    }

    boolean res = matchesAddress(header);
    return res ? MATCH : NO_MATCH;
  }
}