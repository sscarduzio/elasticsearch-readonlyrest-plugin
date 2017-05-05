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
package org.elasticsearch.plugin.readonlyrest.wiring.requestcontext;

import com.google.common.collect.Maps;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;

import java.util.Map;

/**
 * Created by sscarduzio on 04/05/2017.
 */
public class VariablesManager {
  private final Logger logger = Loggers.getLogger(getClass());

  private Map<String, String> headers;

  public VariablesManager(Map<String, String> headers) {
    Map<String, String> map = Maps.newHashMap();
    headers.keySet().stream().forEach(k -> {
      map.put("@" + k.toLowerCase(), headers.get(k));
    });
    this.headers = map;
  }

  public String apply(String original) {
    String destination = original;
    for (String k : headers.keySet()) {
      destination = destination.replace(k, headers.get(k));
    }
    logger.info("variables: replacing " + original + " to: " + destination);
    return destination;
  }
}
