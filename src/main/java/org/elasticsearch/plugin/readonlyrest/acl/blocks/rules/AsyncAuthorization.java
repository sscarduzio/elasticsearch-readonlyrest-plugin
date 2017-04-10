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

  protected abstract CompletableFuture<Boolean> authorize(LoggedUser user, Set<String> groups);
  protected abstract Set<String> getGroups();

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    Optional<LoggedUser> optLoggedInUser = rc.getLoggedInUser();
    if(optLoggedInUser.isPresent()) {
      LoggedUser loggedUser = optLoggedInUser.get();
      return authorize(loggedUser, getGroups()).thenApply(result -> result ? MATCH : NO_MATCH);
    } else {
      logger.warn("Cannot try to authorize user because non is logged now!");
      return CompletableFuture.completedFuture(NO_MATCH);
    }
  }

}
