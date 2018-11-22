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

package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.base.Strings;
import cz.seznam.euphoria.shaded.guava.com.google.common.collect.Sets;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.commons.domain.Value;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.RuleSettings;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class UsersSyncRule extends SyncRule {

  private final Settings settings;
  private final MatcherWithWildcards matcherNoVar;

  public UsersSyncRule(Settings s) {
    if (!s.hasVariables()) {
      this.matcherNoVar = new MatcherWithWildcards(s.getPatternsUnwrapped());
    }
    else {
      this.matcherNoVar = null;
    }

    this.settings = s;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    if (!rc.getLoggedInUser().isPresent() || Strings.isNullOrEmpty(rc.getLoggedInUser().get().getId())) {
      return NO_MATCH;
    }
    String resolvedUser = rc.getLoggedInUser().get().getId();
    if (matcherNoVar != null) {
      return matcherNoVar.match(resolvedUser) ? MATCH : NO_MATCH;
    }

    Set<String> allowedUsers = settings
        .getPatterns()
        .stream()
        .map(v -> v.getValue(rc))
        .flatMap(o -> o.isPresent() ? Stream.of(o.get()) : Stream.empty())
        .collect(Collectors.toSet());

    return new MatcherWithWildcards(allowedUsers).match(resolvedUser) ? MATCH : NO_MATCH;

  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  public static class Settings implements RuleSettings {

    public static final String ATTRIBUTE_NAME = "users";
    private final boolean containsVariables;
    private final Set<Value<String>> patterns;
    private Set<String> patternsUnwrapped;

    public Settings(Set<String> patterns) {
      this.containsVariables = patterns.stream().filter(i -> i.contains("@{")).findFirst().isPresent();
      this.patternsUnwrapped = patterns;
      this.patterns = patternsUnwrapped.stream()
                                       .map(i -> Value.fromString(i, Function.identity()))
                                       .collect(Collectors.toSet());

    }

    public static UsersSyncRule.Settings from(List<String> patterns) {
      return new Settings(Sets.newHashSet(patterns));
    }

    public Set<Value<String>> getPatterns() {
      return patterns;
    }

    public Set<String> getPatternsUnwrapped() {
      return patternsUnwrapped;
    }

    public boolean hasVariables() {
      return containsVariables;
    }

    @Override
    public String getName() {
      return ATTRIBUTE_NAME;
    }
  }
}
