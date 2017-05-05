package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;

/**
 * Created by sscarduzio on 05/05/2017.
 */
public class IndexLevelSecuritySyncRule extends SyncRule {
  @Override
  public RuleExitResult match(RequestContext rc) {
    return MATCH;
  }

  @Override
  public boolean onResponse(BlockExitResult result, RequestContext rc, ActionRequest ar, ActionResponse response) {
    if (!rc.involvesIndices() || !rc.isReadRequest()) {
      return true;
    }
    if (response instanceof MultiSearchResponse){
      MultiSearchResponse msr = (MultiSearchResponse) response;

    }
    return true;
  }
}
