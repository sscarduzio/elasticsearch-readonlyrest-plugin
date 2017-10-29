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

import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authentication;
import tech.beshu.ror.acl.domain.LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.utils.BasicAuthUtils;
import tech.beshu.ror.utils.BasicAuthUtils.BasicAuth;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class AsyncAuthentication extends AsyncRule implements Authentication {

  private final LoggerShim logger;

  protected AsyncAuthentication(ESContext context) {
    logger = context.logger(getClass());
  }

  protected abstract CompletableFuture<Boolean> authenticate(String user, String password);

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    Optional<BasicAuth> optBasicAuth = BasicAuthUtils.getBasicAuthFromHeaders(rc.getHeaders());

    if (optBasicAuth.isPresent() && logger.isDebugEnabled()) {
      try {
        logger.debug("Attempting Login as: " + optBasicAuth.get().getUserName() + " rc: " + rc);
      } catch (IllegalArgumentException e) {
        e.printStackTrace();
      }
    }

    if (!optBasicAuth.isPresent()) {
      logger.debug("Basic auth header not present!");
      return CompletableFuture.completedFuture(NO_MATCH);
    }

    BasicAuth basicAuth = optBasicAuth.get();
    return authenticate(basicAuth.getUserName(), basicAuth.getPassword())
      .thenApply(result -> {
        RuleExitResult r = result != null && result ? MATCH : NO_MATCH;
        if (r.isMatch()) {
          rc.setLoggedInUser(new LoggedUser(basicAuth.getUserName()));
        }
        return r;
      });
  }

}
