package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public abstract class AsyncAuthorization extends AsyncRule {

  protected abstract CompletableFuture<Boolean> authorize(LoggedUser user, Set<String> roles);
  protected abstract Set<String> getRoles();

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    Optional<LoggedUser> optLoggedInUser = rc.getLoggedInUser();
    if(optLoggedInUser.isPresent()) {
      LoggedUser loggedUser = optLoggedInUser.get();
      return authorize(loggedUser, getRoles()).thenApply(result -> result ? MATCH : NO_MATCH);
    } else {
      // todo: log
      return CompletableFuture.completedFuture(NO_MATCH);
    }
  }

}
