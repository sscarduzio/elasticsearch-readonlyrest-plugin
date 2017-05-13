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

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeySha256RuleSettings;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class AuthKeySha256SyncRule extends AuthKeyHashingRule {

  private final AuthKeySha256RuleSettings settings;

  public AuthKeySha256SyncRule(AuthKeySha256RuleSettings s, ESContext context) {
    super(s, context);
    this.settings = s;
  }

  @Override
  protected HashFunction getHashFunction() {
    return Hashing.sha256();
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}
