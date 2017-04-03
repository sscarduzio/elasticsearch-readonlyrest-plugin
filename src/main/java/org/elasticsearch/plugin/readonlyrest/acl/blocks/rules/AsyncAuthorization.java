package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public abstract class AsyncAuthorization extends AsyncRule {
  protected abstract CompletableFuture<Boolean> authorize(LoggedUser user, Set<String> roles);
}
