package org.elasticsearch.plugin.readonlyrest.es53x.actionlisteners;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndexLevelSecuritySyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesRewriteSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SearchlogSyncRule;

import java.util.Optional;

import static org.elasticsearch.plugin.readonlyrest.testutils.RulesUtils.classOfRule;

public class RuleActionListenersProvider {

  private final ImmutableMap<Class<? extends Rule>, RuleActionListener> ruleActionListenerMap;

  public RuleActionListenersProvider(ESContext context) {
    this.ruleActionListenerMap = ImmutableMap.<Class<? extends Rule>, RuleActionListener>builder()
        .put(IndexLevelSecuritySyncRule.class, new IndexLevelSecuritySyncRuleActionListener())
        .put(SearchlogSyncRule.class, new SearchlogSyncRuleActionListener(context))
        .put(IndicesRewriteSyncRule.class, new IndicesRewriteSyncRuleActionListener(context))
        .build();
  }

  public Optional<RuleActionListener> getActionListenerOf(Rule rule) {
    return Optional.ofNullable(ruleActionListenerMap.get(classOfRule(rule)));
  }
}
