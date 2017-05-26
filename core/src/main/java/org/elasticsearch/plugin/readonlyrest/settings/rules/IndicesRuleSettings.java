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
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class IndicesRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "indices";

  private final Set<Value<String>> indices;

  public static IndicesRuleSettings from(Set<String> indices) {
    return new IndicesRuleSettings(
        indices.stream()
            .map(i -> Value.fromString(i, Function.identity()))
            .collect(Collectors.toSet())
    );
  }

  private IndicesRuleSettings(Set<Value<String>> indices) {
    this.indices = indices;
  }

  public Set<Value<String>> getIndices() {
    return indices;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
