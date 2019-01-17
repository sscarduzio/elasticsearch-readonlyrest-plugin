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
import tech.beshu.ror.commons.domain.__old_Value;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.RuleSettings;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SnapshotsSyncRule extends SyncRule {

  private final Settings settings;
  private final ESContext context;

  public SnapshotsSyncRule(Settings s, ESContext context) {
    this.settings = s;
    this.context = context;
  }

  @Override
  public RuleExitResult match(__old_RequestContext rc) {

    if(!rc.getAction().contains("/snapshot/")){
      return MATCH;
    }

    Set<String> allowedSnapshots = settings.getAllowedSnapshots(rc);

    // Shortcut if we are matching all (i.e. totally useless rule, should be removed from settings)
    if(allowedSnapshots.contains("_all") || allowedSnapshots.contains("*")){
    context.logger(this.getClass()).warn("Setting up a rule that matches all the values is redundant. Remove this rule from the __old_ACL block.");
      return MATCH;
    }

    Set<String> requestedSnapshots = rc.getSnapshots();

    Set<String> alteredSnapshots = ZeroKnowledgeMatchFilter.alterIndicesIfNecessary(requestedSnapshots, new MatcherWithWildcards(allowedSnapshots));

    // no changes needed
    if(alteredSnapshots == null){
      return MATCH;
    }

    // Nothing survived the filter, and we should forbid
    if(alteredSnapshots.isEmpty()){
      return NO_MATCH;
    }

    // Apply modifications only to read requests, the others can be happily bounced.
    if(rc.isReadRequest()){
      rc.setSnapshots(alteredSnapshots);
      return MATCH;
    }

    return NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  public static class Settings implements RuleSettings {

    public static final String ATTRIBUTE_NAME = "snapshots";

    private final Set<__old_Value<String>> allowedSnapshots;
    private final boolean containsVariables;
    private Set<String> unwrapped;

    public Settings(Set<__old_Value<String>> allowedSnapshots) {
      this.containsVariables = allowedSnapshots.stream().filter(i -> i.getTemplate().contains("@{")).findFirst().isPresent();
      this.allowedSnapshots = allowedSnapshots;
      if (!containsVariables) {
        this.unwrapped = allowedSnapshots.stream().map(__old_Value::getTemplate).collect(Collectors.toSet());
      }
    }

    public static Settings fromBlockSettings(RawSettings blockSettings) {
      return new Settings(
          blockSettings.notEmptyListReq(ATTRIBUTE_NAME).stream()
                       .map(obj -> __old_Value.fromString((String) obj, Function.identity()))
                       .collect(Collectors.toSet())
      );
    }

    public Set<String> getAllowedSnapshots(__old_Value.__old_VariableResolver rc) {
      if (!containsVariables) {
        return unwrapped;
      }
      return allowedSnapshots.stream().map(v -> v.getValue(rc)).filter(o -> o.isPresent()).map(o -> o.get()).collect(Collectors.toSet());
    }

    @Override
    public String getName() {
      return ATTRIBUTE_NAME;
    }

  }
}
