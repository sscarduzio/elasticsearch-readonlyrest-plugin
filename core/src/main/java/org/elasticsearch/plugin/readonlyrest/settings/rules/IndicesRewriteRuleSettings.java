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

import com.google.common.base.Strings;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class IndicesRewriteRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "indices_rewrite";

  private final Set<Pattern> targetPatterns;
  private final Value<String> replacement;

  public static IndicesRewriteRuleSettings from(List<String> list) {
    if (list.size() < 2) throw new SettingsMalformedException("Minimum two arguments required for " + ATTRIBUTE_NAME +
        ". I.e. [target1, target2, replacement]");
    return new IndicesRewriteRuleSettings(
        list.subList(0, list.size() - 1).stream()
            .distinct()
            .filter(v -> !Strings.isNullOrEmpty(v))
            .map(Pattern::compile)
            .collect(Collectors.toSet()),
        list.get(list.size() - 1)
    );
  }

  private IndicesRewriteRuleSettings(Set<Pattern> targetPatterns, String replacement) {
    this.targetPatterns = targetPatterns;
    this.replacement = Value.fromString(replacement, Function.identity());
  }

  public Set<Pattern> getTargetPatterns() {
    return targetPatterns;
  }

  public Value<String> getReplacement() {
    return replacement;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
