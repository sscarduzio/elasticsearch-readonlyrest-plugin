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

package tech.beshu.ror.acl.blocks.rules.impl;

import tech.beshu.ror.commons.shims.ESContext;
import tech.beshu.ror.commons.shims.LoggerShim;
import tech.beshu.ror.acl.blocks.rules.BasicAuthentication;
import tech.beshu.ror.settings.rules.AuthKeyPlainTextRuleSettings;
import tech.beshu.ror.utils.BasicAuthUtils.BasicAuth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class AuthKeySyncRule extends BasicAuthentication {

  private final LoggerShim logger;
  private final AuthKeyPlainTextRuleSettings settings;

  public AuthKeySyncRule(AuthKeyPlainTextRuleSettings s, ESContext context) {
    super(s, context);
    this.logger = context.logger(AuthKeySyncRule.class);
    this.settings = s;
  }

  @Override
  protected boolean authenticate(String configuredAuthKey, BasicAuth basicAuth) {
    try {
      String decodedProvided = new String(Base64.getDecoder().decode(basicAuth.getBase64Value()), StandardCharsets.UTF_8);
      return decodedProvided.equals(configuredAuthKey);
    } catch (Throwable e) {
      logger.warn("Exception while authentication", e);
      return false;
    }
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}