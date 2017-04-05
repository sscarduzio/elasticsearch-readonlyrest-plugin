package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;

import java.util.concurrent.CompletableFuture;

public class AsyncRuleAdapter extends AsyncRule {

  private final SyncRule underlying;

  public AsyncRuleAdapter(SyncRule underlying) {
    this.underlying = underlying;
  }

  public static AsyncRule wrap(SyncRule rule) {
    return new AsyncRuleAdapter(rule);
  }

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    return CompletableFuture.completedFuture(underlying.match(rc));
  }

  @Override
  public String getKey() {
    return underlying.getKey();
  }

  public SyncRule getUnderlying() {
    return underlying;
  }
}
