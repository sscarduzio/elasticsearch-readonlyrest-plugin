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

import tech.beshu.ror.acl.domain.__old_Value;
import tech.beshu.ror.settings.RuleSettings;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class __old_IndicesRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "indices";

  private final Set<__old_Value<String>> indices;
  private final Set<String> indicesUnwrapped;
  private boolean containsVariables;

  private __old_IndicesRuleSettings(Set<String> indices) {
    this.indicesUnwrapped = indices;
    containsVariables = indices.stream().filter(i -> i.contains("@{")).findFirst().isPresent();
    this.indices = indices.stream()
      .map(i -> __old_Value.fromString(i, Function.identity()))
      .collect(Collectors.toSet());
  }

  public static __old_IndicesRuleSettings from(Set<String> indices) {
    return new __old_IndicesRuleSettings(indices);
  }

  public boolean hasVariables() {
    return containsVariables;
  }

  public Set<__old_Value<String>> getIndices() {
    return indices;
  }

  public Set<String> getIndicesUnwrapped() {
    return indicesUnwrapped;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
