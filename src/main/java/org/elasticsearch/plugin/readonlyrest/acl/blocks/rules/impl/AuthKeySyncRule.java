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

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class AuthKeySyncRule extends GeneralAuthKeySyncRule {
  private static final Logger logger = Loggers.getLogger(AuthKeySyncRule.class);

  public AuthKeySyncRule(Settings s) throws RuleNotConfiguredException {
    super(s);
  }

  @Override
  protected boolean authenticate(String configured, String providedBase64) {
    try {
    String decodedProvided = new String(Base64.getDecoder().decode(providedBase64), StandardCharsets.UTF_8);
    return decodedProvided.equals(configured);
    } catch (Throwable e) {
      logger.warn("Exception while authentication", e);
      return false;
    }
  }
}