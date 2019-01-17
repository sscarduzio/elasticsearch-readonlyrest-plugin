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
import tech.beshu.ror.commons.domain.__old_Value;
import tech.beshu.ror.settings.RuleSettings;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class __old_XForwardedForRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "x_forwarded_for";

  private final Set<__old_Value<String>> allowedIdentifiers;

  private __old_XForwardedForRuleSettings(Set<__old_Value<String>> allowedIdentifiers) {
    this.allowedIdentifiers = allowedIdentifiers;
  }

  public static __old_XForwardedForRuleSettings from(List<String> hosts) {
    Set<__old_Value<String>> identifiers = Sets.newHashSet();
    hosts.stream()
         .forEach(allowedHost -> identifiers.add(__old_Value.fromString(allowedHost, Function.identity())));

    return new __old_XForwardedForRuleSettings(identifiers);
  }

  public Set<__old_Value<String>> getAllowedIdentifiers() {
    return allowedIdentifiers;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }

}
