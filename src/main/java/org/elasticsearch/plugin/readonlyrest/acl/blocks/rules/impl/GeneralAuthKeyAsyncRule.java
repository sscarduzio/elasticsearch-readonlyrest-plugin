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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public abstract class GeneralAuthKeyAsyncRule extends AsyncRule {
  private static final ESLogger logger = Loggers.getLogger(GeneralAuthKeyAsyncRule.class);

  protected abstract CompletableFuture<Boolean> authenticate(String user, String password);

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    String authHeader = BasicAuthUtils.extractAuthFromHeader(rc.getHeaders().get("Authorization"));

    if (authHeader != null && logger.isDebugEnabled()) {
      try {
        logger.info("Login as: " + BasicAuthUtils.getBasicAuthUser(rc.getHeaders()) + " rc: " + rc);
      } catch (IllegalArgumentException e) {
        e.printStackTrace();
      }
    }

    if (authHeader == null) {
      return CompletableFuture.completedFuture(NO_MATCH);
    }

    String val = authHeader.trim();
    if (val.length() == 0) {
      return CompletableFuture.completedFuture(NO_MATCH);
    }

    String decodedProvided = new String(Base64.getDecoder().decode(authHeader), StandardCharsets.UTF_8);
    String[] authData = decodedProvided.split(":");
    if (authData.length != 2) {
      return CompletableFuture.completedFuture(NO_MATCH);
    }

    return authenticate(authData[0], authData[1])
      .thenApply(result -> result != null && result ? MATCH : NO_MATCH);
  }

}
