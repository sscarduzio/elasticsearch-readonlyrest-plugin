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
package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.acl.domain.IPMask;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class XForwardedForRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "x_forwarded_for";

  private final Set<Value<IPMask>> allowedAddresses;

  public static XForwardedForRuleSettings from(List<String> addresses) {
    return new XForwardedForRuleSettings(addresses.stream()
        .map(obj -> Value.fromString(obj, ipMaskFromString))
        .collect(Collectors.toSet()));
  }

  private XForwardedForRuleSettings(Set<Value<IPMask>> allowedAddresses) {
    this.allowedAddresses = allowedAddresses;
  }

  public Set<Value<IPMask>> getAllowedAddresses() {
    return allowedAddresses;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }

  private static Function<String, IPMask> ipMaskFromString = value -> {
    try {
      return IPMask.getIPMask(value);
    } catch (UnknownHostException e) {
      throw new SettingsMalformedException("Cannot create IP address from string: " + value);
    }
  };
}
