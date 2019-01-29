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
import tech.beshu.ror.acl.domain.__old_LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.__old_AuthKeyRuleSettings;
import tech.beshu.ror.utils.__old_BasicAuthUtils;
import tech.beshu.ror.utils.__old_BasicAuthUtils.__old_BasicAuth;

import java.util.NoSuchElementException;
import java.util.Optional;

public abstract class __old_BasicAuthentication extends SyncRule implements Authentication {

  private final LoggerShim logger;
  private final String authKey;

  public __old_BasicAuthentication(__old_AuthKeyRuleSettings s, ESContext context) {
    this.logger = context.logger(getClass());
    this.authKey = s.getAuthKey();
  }

  protected abstract boolean authenticate(String configuredAuthKey, __old_BasicAuth basicAuth);

  @Override
  public RuleExitResult match(__old_RequestContext rc) {
    Optional<__old_BasicAuth> optBasicAuth = __old_BasicAuthUtils.getBasicAuthFromHeaders(rc.getHeaders());

    if (optBasicAuth.isPresent() && logger.isDebugEnabled()) {
      try {
        logger.info("Attempting Login as: " + optBasicAuth.get().getUserName() + " rc: " + rc);
      } catch (NoSuchElementException e) {
        logger.error("No basic auth");
      }
    }

    if (authKey == null || !optBasicAuth.isPresent()) {
      logger.debug("Basic auth header or auth key not present!");
      return NO_MATCH;
    }

    __old_BasicAuthUtils.__old_BasicAuth basicAuth = optBasicAuth.get();
    RuleExitResult res = authenticate(authKey, basicAuth) ? MATCH : NO_MATCH;
    if (res.isMatch()) {
      rc.setLoggedInUser(new __old_LoggedUser(basicAuth.getUserName()));
    }
    return res;
  }

}
