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

import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.httpclient.HttpMethod;
import tech.beshu.ror.settings.RuleSettings;

import java.util.Set;
import java.util.stream.Collectors;

public class MethodsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "methods";

  private final Set<HttpMethod> methods;

  public MethodsRuleSettings(Set<HttpMethod> methods) {
    this.methods = methods;
  }

  public static MethodsRuleSettings from(Set<String> methods) {
    return new MethodsRuleSettings(methods.stream()
                                     .map(value -> HttpMethod.fromString(value)
                                       .<SettingsMalformedException>orElseThrow(() -> new SettingsMalformedException("Unknown/unsupported http method: " + value)))
                                     .collect(Collectors.toSet()));
  }

  public Set<HttpMethod> getMethods() {
    return methods;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}