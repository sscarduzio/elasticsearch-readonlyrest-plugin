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

import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.commons.domain.Value;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.RuleSettings;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RepositoriesSyncRule extends SyncRule {

  private final Settings settings;
  private final ESContext context;

  public RepositoriesSyncRule(Settings s, ESContext context) {
    this.settings = s;
    this.context = context;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {

    if(!rc.getAction().contains("/repository/")){
      return MATCH;
    }

    Set<String> allowedRepositories = settings.getAllowedRepositories(rc);

    // Shortcut if we are matching all (i.e. totally useless rule, should be removed from settings)
    if(allowedRepositories.contains("_all") || allowedRepositories.contains("*")){
      context.logger(this.getClass()).warn("Setting up a rule that matches all the values is redundant. Remove this rule from the ACL block.");
      return MATCH;
    }

    Set<String> requestedRepositories = rc.getRepositories();

    Set<String> alteredRepositories = ZeroKnowledgeMatchFilter.alterIndicesIfNecessary(requestedRepositories, new MatcherWithWildcards(allowedRepositories));

    // no changes needed
    if(alteredRepositories == null){
      return MATCH;
    }

    // Nothing survived the filter, and we should forbid
    if(alteredRepositories.isEmpty()){
      return NO_MATCH;
    }

    // Apply modifications only to read requests, the others can be happily bounced.
    if(rc.isReadRequest()){
      rc.setRepositories(alteredRepositories);
      return MATCH;
    }

    return NO_MATCH;  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  public static class Settings implements RuleSettings {

    public static final String ATTRIBUTE_NAME = "repositories";

    private final Set<Value<String>> allowedRepositories;
    private final boolean containsVariables;
    private Set<String> unwrapped;

    public Settings(Set<Value<String>> allowedRepositories) {
      this.containsVariables = allowedRepositories.stream().filter(i -> i.getTemplate().contains("@{")).findFirst().isPresent();
      this.allowedRepositories = allowedRepositories;
      if (!containsVariables) {
        this.unwrapped = allowedRepositories.stream().map(Value::getTemplate).collect(Collectors.toSet());
      }
    }

    public static Settings fromBlockSettings(RawSettings blockSettings) {
      return new Settings(
          blockSettings.notEmptyListReq(ATTRIBUTE_NAME).stream()
                       .map(obj -> Value.fromString((String) obj, Function.identity()))
                       .collect(Collectors.toSet())
      );
    }

    public Set<String> getAllowedRepositories(Value.VariableResolver rc) {
      if (!containsVariables) {
        return unwrapped;
      }
      return allowedRepositories.stream().map(v -> v.getValue(rc)).filter(o -> o.isPresent()).map(o -> o.get()).collect(Collectors.toSet());
    }

    @Override
    public String getName() {
      return ATTRIBUTE_NAME;
    }

  }
}
