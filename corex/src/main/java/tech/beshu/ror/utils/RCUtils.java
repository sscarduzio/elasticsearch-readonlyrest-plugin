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

package tech.beshu.ror.utils;

import cz.seznam.euphoria.shaded.guava.com.google.common.collect.Sets;

import java.util.regex.Pattern;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public class RCUtils {
  /*
   * A regular expression to match the various representations of "localhost"
   */

  public static final String LOCALHOST = "127.0.0.1";

  private static final Pattern localhostRe = Pattern.compile("^(127(\\.\\d+){1,3}|[0:]+1)$");

  private static MatcherWithWildcards readRequestMatcher = new MatcherWithWildcards(Sets.newHashSet(
      "cluster:monitor/*",
      "cluster:*resolve*",
      "cluster:*search*",
      "indices:admin/aliases/exists",
      "indices:admin/aliases/resolve",
      "indices:admin/exists*",
      "indices:admin/resolve*",
      "indices:admin/mappings/fields/resolve*",
      "indices:admin/mappings/resolve*",
      "indices:admin/refresh*",
      "indices:admin/types/exists",
      "indices:admin/validate/*",
      "indices:data/read/*",
      "cluster:admin/*/resolve",
      "cluster:admin/*/status"

  ));

  public static boolean isReadRequest(String action) {
    return readRequestMatcher.match(action);
  }

  public static boolean isLocalHost(String remoteHost) {
    return localhostRe.matcher(remoteHost).find();
  }

}
