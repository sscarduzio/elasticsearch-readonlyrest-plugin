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

package org.elasticsearch.plugin.readonlyrest.wiring;

import com.google.common.collect.Maps;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;

import java.util.Map;

/**
 * Created by sscarduzio on 25/11/2016.
 */
public class ThreadRepo {
  public static ThreadLocal<RestRequest> request = new ThreadLocal<>();
  public static ThreadLocal<RestChannel> channel = new ThreadLocal<>();
  public static ThreadLocal<Map<String, String>> history = new ThreadLocal<>();

  public static void resetHistory() {
    if (history.get() != null) {
      history.get().clear();
    } else {
      history.set(Maps.newHashMap());
    }
  }
}
