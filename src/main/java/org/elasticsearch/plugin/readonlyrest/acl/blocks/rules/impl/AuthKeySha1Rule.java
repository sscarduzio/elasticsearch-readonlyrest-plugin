/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.hash.Hashing;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class AuthKeySha1Rule extends AuthKeyRule {

  public AuthKeySha1Rule(Settings s) throws RuleNotConfiguredException {
    super(s);
    try {
      authKey = new String(Base64.getDecoder().decode(authKey), StandardCharsets.UTF_8);
    } catch (Throwable e) {
      throw new ElasticsearchParseException("cannot parse configuration for: " + this.getKey());
    }
  }

  @Override
  protected boolean checkEqual(String provided) {
    try {
      String decodedProvided = new String(Base64.getDecoder().decode(provided), StandardCharsets.UTF_8);
      String shaProvided = Hashing.sha1().hashString(decodedProvided, Charset.defaultCharset()).toString();
      return authKey.equals(shaProvided);
    } catch (Throwable e) {
      return false;
    }
  }
}
