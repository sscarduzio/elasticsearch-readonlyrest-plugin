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
import inet.ipaddr.IPAddressString;
import tech.beshu.ror.commons.domain.IPMask;
import tech.beshu.ror.commons.domain.Value;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.settings.RuleSettings;

import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class XForwardedForRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "x_forwarded_for";

  private final Set<Value<String>> allowedIdentifiers;

  private XForwardedForRuleSettings(Set<Value<String>> allowedIdentifiers) {
    this.allowedIdentifiers = allowedIdentifiers;
  }

  public static XForwardedForRuleSettings from(List<String> hosts) {
    Set<Value<String>> identifiers = Sets.newHashSet();
    hosts.stream()
      .forEach(allowedHost -> identifiers.add(Value.fromString(allowedHost, Function.identity())));

    return new XForwardedForRuleSettings(identifiers);
  }

  public Set<Value<String>> getAllowedIdentifiers() {
    return allowedIdentifiers;
  }


  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }

}
