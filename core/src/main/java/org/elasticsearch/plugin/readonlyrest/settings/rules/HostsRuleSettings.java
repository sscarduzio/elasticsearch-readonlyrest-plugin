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
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class HostsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "hosts";
  public static final String ATTRIBUTE_ACCEPT_X_FORWARDED_FOR_HEADER = "accept_x-forwarded-for_header";

  private static final boolean DEFAULT_ACCEPT_X_FOWARDED_FOR = false;

  private final Set<Value<String>> allowedAddresses;
  private final boolean acceptXForwardedForHeader;

  private HostsRuleSettings(Set<Value<String>> allowedAddresses, boolean acceptXForwardedForHeader) {
    this.allowedAddresses = allowedAddresses;
    this.acceptXForwardedForHeader = acceptXForwardedForHeader;
  }

  public static HostsRuleSettings fromBlockSettings(RawSettings blockSettings) {
    return new HostsRuleSettings(
      blockSettings.notEmptyListReq(ATTRIBUTE_NAME).stream()
        .map(obj -> Value.fromString((String) obj, Function.identity()))
        .collect(Collectors.toSet()),
      blockSettings.booleanOpt(ATTRIBUTE_ACCEPT_X_FORWARDED_FOR_HEADER).orElse(DEFAULT_ACCEPT_X_FOWARDED_FOR)
    );
  }

  public Set<Value<String>> getAllowedAddresses() {
    return allowedAddresses;
  }

  public boolean isAcceptXForwardedForHeader() {
    return acceptXForwardedForHeader;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }


}
