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

import tech.beshu.ror.commons.domain.Value;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.settings.RuleSettings;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class LocalHostsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "hosts_local";


  private final Set<Value<String>> allowedAddresses;

  public LocalHostsRuleSettings(Set<Value<String>> allowedAddresses) {
    this.allowedAddresses = allowedAddresses;
  }

  public static LocalHostsRuleSettings fromBlockSettings(RawSettings blockSettings) {
    return new LocalHostsRuleSettings(
      blockSettings.notEmptyListReq(ATTRIBUTE_NAME).stream()
        .map(obj -> Value.fromString((String) obj, Function.identity()))
        .collect(Collectors.toSet())
    );
  }

  public Set<Value<String>> getAllowedAddresses() {
    return allowedAddresses;
  }


  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }


}
