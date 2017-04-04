package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authorization;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public abstract class AsyncAuthorization extends AsyncRule implements Authorization {

  private static final Logger logger = Loggers.getLogger(AsyncAuthorization.class);

  protected abstract CompletableFuture<Boolean> authorize(LoggedUser user, Set<String> roles);
  protected abstract Set<String> getRoles();

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    Optional<LoggedUser> optLoggedInUser = rc.getLoggedInUser();
    if(optLoggedInUser.isPresent()) {
      LoggedUser loggedUser = optLoggedInUser.get();
      return authorize(loggedUser, getRoles()).thenApply(result -> result ? MATCH : NO_MATCH);
    } else {
      logger.warn("Cannot try to authorize user because non is logged now!");
      return CompletableFuture.completedFuture(NO_MATCH);
    }
  }

}
