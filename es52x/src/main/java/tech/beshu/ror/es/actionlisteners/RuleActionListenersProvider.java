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
package tech.beshu.ror.es.actionlisteners;

import com.google.common.collect.ImmutableMap;
import tech.beshu.ror.commons.shims.ESContext;
import tech.beshu.ror.acl.blocks.rules.Rule;
import tech.beshu.ror.acl.blocks.rules.impl.IndexLevelSecuritySyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.IndicesRewriteSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.SearchlogSyncRule;

import java.util.Optional;

import static tech.beshu.ror.utils.RulesUtils.classOfRule;

public class RuleActionListenersProvider {

  private final ImmutableMap<Class<? extends Rule>, RuleActionListener<?>> ruleActionListenerMap;

  public RuleActionListenersProvider(ESContext context) {
    this.ruleActionListenerMap = ImmutableMap.<Class<? extends Rule>, RuleActionListener<?>>builder()
      .put(IndexLevelSecuritySyncRule.class, new IndexLevelSecuritySyncRuleActionListener())
      .put(SearchlogSyncRule.class, new SearchlogSyncRuleActionListener(context))
      .put(IndicesRewriteSyncRule.class, new IndicesRewriteSyncRuleActionListener(context))
      .build();
  }

  public Optional<RuleActionListener<?>> getActionListenerOf(Rule rule) {
    return Optional.ofNullable(ruleActionListenerMap.get(classOfRule(rule)));
  }
}
