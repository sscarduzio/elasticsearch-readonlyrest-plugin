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
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeyRuleSettings;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils.BasicAuth;

import java.util.Optional;

public abstract class BasicAuthentication extends SyncRule implements UserRule, Authentication {

  private final Logger logger;
  private final String authKey;

  public BasicAuthentication(AuthKeyRuleSettings s, ESContext context) {
    this.logger = context.logger(getClass());
    ConfigurationHelper.setRequirePassword(true);
    this.authKey = s.getAuthKey();
  }

  protected abstract boolean authenticate(String configuredAuthKey, BasicAuth basicAuth);

  @Override
  public RuleExitResult match(RequestContext rc) {
    Optional<BasicAuth> optBasicAuth = BasicAuthUtils.getBasicAuthFromHeaders(rc.getHeaders());

    if (optBasicAuth.isPresent() && logger.isDebugEnabled()) {
      try {
        logger.info("Attempting Login as: " + optBasicAuth.get().getUserName() + " rc: " + rc);
      } catch (IllegalArgumentException e) {
        e.printStackTrace();
      }
    }

    if (authKey == null || !optBasicAuth.isPresent()) {
      logger.debug("Basic auth header or auth key not present!");
      return NO_MATCH;
    }

    BasicAuth basicAuth = optBasicAuth.get();
    RuleExitResult res = authenticate(authKey, basicAuth) ? MATCH : NO_MATCH;
    if (res.isMatch()) {
      rc.setLoggedInUser(new LoggedUser(basicAuth.getUserName()));
    }
    return res;
  }

}
