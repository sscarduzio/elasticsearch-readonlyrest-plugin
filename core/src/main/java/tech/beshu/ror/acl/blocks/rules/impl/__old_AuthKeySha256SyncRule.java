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

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.settings.rules.AuthKeySha256RuleSettings;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class __old_AuthKeySha256SyncRule extends __old_AuthKeyHashingRule {

  private final AuthKeySha256RuleSettings settings;

  public __old_AuthKeySha256SyncRule(AuthKeySha256RuleSettings s, ESContext context) {
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
