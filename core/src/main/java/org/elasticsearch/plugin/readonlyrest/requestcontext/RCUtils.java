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

package org.elasticsearch.plugin.readonlyrest.requestcontext;

import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.acl.domain.MatcherWithWildcards;

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
      "cluster:*get*",
      "cluster:*search*",
      "indices:admin/aliases/exsists",
      "indices:admin/aliases/get",
      "indices:admin/exists*",
      "indices:admin/get*",
      "indices:admin/mappings/fields/get*",
      "indices:admin/mappings/get*",
      "indices:admin/refresh*",
      "indices:admin/types/exists",
      "indices:admin/validate/*",
      "indices:monitor/*",
      "indices:data/read/*"
  ));

  public static boolean isReadRequest(String action) {
    return readRequestMatcher.match(action);
  }

  public static boolean isLocalHost(String remoteHost) {
    return localhostRe.matcher(remoteHost).find();
  }

}
