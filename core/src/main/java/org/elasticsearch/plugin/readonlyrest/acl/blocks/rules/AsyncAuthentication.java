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
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils.BasicAuth;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class AsyncAuthentication extends AsyncRule implements Authentication {

  private final Logger logger;

  protected AsyncAuthentication(ESContext context) {
    logger = context.logger(getClass());
  }

  protected abstract CompletableFuture<Boolean> authenticate(String user, String password);

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    Optional<BasicAuth> optBasicAuth = BasicAuthUtils.getBasicAuthFromHeaders(rc.getHeaders());

    if (optBasicAuth.isPresent() && logger.isDebugEnabled()) {
      try {
        logger.info("Attempting Login as: " + optBasicAuth.get().getUserName() + " rc: " + rc);
      } catch (IllegalArgumentException e) {
        e.printStackTrace();
      }
    }

    if (!optBasicAuth.isPresent()) {
      logger.warn("Basic auth header not present!");
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
