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
package tech.beshu.ror.settings.rules;

import cz.seznam.euphoria.shaded.guava.com.google.common.collect.Sets;
import cz.seznam.euphoria.shaded.guava.com.google.common.net.InetAddresses;
import tech.beshu.ror.acl.domain.IPMask;
import tech.beshu.ror.acl.domain.Value;
import tech.beshu.ror.settings.RuleSettings;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class XForwardedForRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "x_forwarded_for";

  private final Set<Value<String>> allowedIdentifiers;
  private final Set<IPMask> allowedNumeric;

  private XForwardedForRuleSettings(Set<Value<String>> allowedIdentifiers, Set<IPMask> allowedNumeric) {
    this.allowedIdentifiers = allowedIdentifiers;
    this.allowedNumeric = allowedNumeric;
  }

  public static XForwardedForRuleSettings from(List<String> hosts) {
    Set<Value<String>> identifiers = Sets.newHashSet();
    Set<IPMask> numerics = Sets.newHashSet();
    hosts.stream()
      .forEach(allowedHost -> {
        if (!isInetAddressOrBlock(allowedHost)) {
          identifiers.add(Value.fromString(allowedHost, Function.identity()));
        }
        else {
          try {
            numerics.add(IPMask.getIPMask(allowedHost));
          } catch (UnknownHostException e) {
            e.printStackTrace();
          }
        }
      });

    return new XForwardedForRuleSettings(identifiers, numerics);
  }

  public static boolean isInetAddressOrBlock(String address) {
    int slash = address.lastIndexOf('/');
    if (slash != -1) {
      address = address.substring(0, slash);
    }
    return InetAddresses.isInetAddress(address);
  }

  public Set<Value<String>> getAllowedIdentifiers() {
    return allowedIdentifiers;
  }

  public Set<IPMask> getAllowedNumeric() {
    return allowedNumeric;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
