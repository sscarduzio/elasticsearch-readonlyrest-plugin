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

package tech.beshu.ror.acl.blocks.rules;

import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authorization;
import tech.beshu.ror.commons.domain.LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.__old_RequestContext;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class AsyncAuthorization extends AsyncRule implements Authorization {

  private final LoggerShim logger;

  protected AsyncAuthorization(ESContext context) {
    logger = context.logger(getClass());
  }

  protected abstract CompletableFuture<Boolean> authorize(LoggedUser user);

  @Override
  public CompletableFuture<RuleExitResult> match(__old_RequestContext rc) {
    Optional<LoggedUser> optLoggedInUser = rc.getLoggedInUser();
    if (optLoggedInUser.isPresent()) {
      LoggedUser loggedUser = optLoggedInUser.get();
      loggedUser.resolveCurrentGroup(rc.getHeaders());

      CompletableFuture<RuleExitResult> res = authorize(loggedUser).thenApply(result -> result ? MATCH : NO_MATCH);
      return res;

    }
    else {
      logger.warn("Cannot try to authorize user because non is logged now!");
      return CompletableFuture.completedFuture(NO_MATCH);
    }
  }

}

