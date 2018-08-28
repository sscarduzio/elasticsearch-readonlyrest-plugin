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

import java.util.Set;

/**
 * Created by sscarduzio on 14/02/2016.
 * This is a clone of headers rule (now deprecated), as it also evaluates arguments in logical AND
 */
public class HeadersAndSyncRule extends HeadersSyncRule {

  public HeadersAndSyncRule(Settings s) {
    super(s);
  }

  @Override
  public String getKey() {
    return super.settings.getName();
  }

  public static class Settings extends HeadersSyncRule.Settings {
    public static final String ATTRIBUTE_NAME = "headers_and";

    public Settings(Set<String> headersWithValue) {
      super(headersWithValue);
    }

    @Override
    public String getName() {
      return ATTRIBUTE_NAME;
    }

  }
}
