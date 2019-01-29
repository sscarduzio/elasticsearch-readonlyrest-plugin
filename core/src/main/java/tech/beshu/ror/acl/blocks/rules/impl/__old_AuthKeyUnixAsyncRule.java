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

import tech.beshu.ror.acl.blocks.rules.AsyncAuthentication;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.settings.rules.__old_AuthKeyUnixRuleSettings;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.codec.digest.Crypt.crypt;


public class __old_AuthKeyUnixAsyncRule extends AsyncAuthentication {

  private final LoggerShim logger;
  private final __old_AuthKeyUnixRuleSettings settings;

  public __old_AuthKeyUnixAsyncRule(__old_AuthKeyUnixRuleSettings s, ESContext context) {
    super(context);
    this.logger = context.logger(__old_AuthKeyUnixAsyncRule.class);
    this.settings = s;
  }

  public boolean authenticateSync(String username, String password) {

    try {
      // Fast exit if the username is wrong
      if (!settings.getAuthKey().startsWith(username + ":")) {
        return false;
      }
      String decodedProvided = roundHash(settings.getAuthKey().split(":"), new String[]{username, password});
      return decodedProvided.equals(settings.getAuthKey());
    } catch (Throwable e) {
      logger.warn("Exception while authentication", e);
      return false;
    }
  }

  private String roundHash(String[] key, String[] login) {
    Pattern p = Pattern.compile("((?:[^$]*\\$){3}[^$]*).*");
    Matcher m = p.matcher(key[1]);
    String result = "";
    if (m.find()) {
      result = login[0] + ":" + crypt(login[1], m.group(1));
    }
    return result;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  @Override
  protected CompletableFuture<Boolean> authenticate(String username, String password) {
    return CompletableFuture.completedFuture(authenticateSync(username, password));
  }

}