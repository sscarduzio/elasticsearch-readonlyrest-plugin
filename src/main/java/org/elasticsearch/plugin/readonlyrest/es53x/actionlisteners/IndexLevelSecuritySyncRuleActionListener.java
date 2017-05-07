package org.elasticsearch.plugin.readonlyrest.es53x.actionlisteners;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndexLevelSecuritySyncRule;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;

public class IndexLevelSecuritySyncRuleActionListener extends RuleActionListener<IndexLevelSecuritySyncRule> {

  public IndexLevelSecuritySyncRuleActionListener() {
    super(IndexLevelSecuritySyncRule.class);
  }

  @Override
  protected boolean onResponse(BlockExitResult result,
                               RequestContext rc,
                               ActionRequest ar,
                               ActionResponse response,
                               IndexLevelSecuritySyncRule rule) {
    if (!rc.involvesIndices() || !rc.isReadRequest()) {
      return true;
    }
    if (response instanceof MultiSearchResponse){
      MultiSearchResponse msr = (MultiSearchResponse) response;

    }
    return true;
  }

  @Override
  protected boolean onFailure(BlockExitResult result,
                              RequestContext rc,
                              ActionRequest ar,
                              Exception e,
                              IndexLevelSecuritySyncRule rule) {
    return true;
  }
}
