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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Created by sscarduzio on 04/05/2017.
 */
public class VariablesManager {

  private static final char ESCAPE_CHAR = '@';
  private static final char DELIMITER_BEGIN_CHAR = '{';
  private static final char DELIMITER_END_CHAR = '}';
  private static final String VAR_DETECTOR = new StringBuilder(2).append(ESCAPE_CHAR).append(DELIMITER_BEGIN_CHAR).toString();

  private final Logger logger = Loggers.getLogger(getClass());
  private final IndicesRequestContext rc;
  private Map<String, String> headers;

  public VariablesManager(Map<String, String> headers, IndicesRequestContext rc) {
    this.rc = rc;
    Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.keySet().stream().forEach(k -> {
      map.put(k.toLowerCase(), headers.get(k));
    });
    this.headers = map;
  }

  public static boolean containsReplacements(String template) {
    return template.contains(VAR_DETECTOR);
  }

  public Optional<String> apply(String original) {
    if (!containsReplacements(original)) {
      return Optional.of(original);
    }

    // Replacing "user" is more frequent, take care of that first.
    String replaced = original;
    if (rc.getLoggedInUser().isPresent()) {
      Map<String, String> m = new HashMap(1);
      m.put("user", rc.getLoggedInUser().get().getId());
      replaced = replace(m, replaced);
    }

    if (!containsReplacements(replaced)) {
      return Optional.of(replaced);
    }

    replaced = replace(headers, original);

    if (!containsReplacements(replaced)) {
      return Optional.of(replaced);
    }

    logger.debug("unable to replace all variables, failing.  orig:" + original + " replaced: " + replaced);
    return Optional.empty();
  }

  /**
   * Uber-fast regex-free template replacer
   *
   * @param map
   * @param str
   * @return
   */
  private String replace(Map<String, String> map, String str) {
    StringBuilder sb = new StringBuilder();
    char[] strArray = str.toCharArray();
    int i = 0;
    while (i < strArray.length - 1) {
      if (strArray[i] == ESCAPE_CHAR && strArray[i + 1] == DELIMITER_BEGIN_CHAR) {
        i = i + 2;
        int begin = i;
        while (strArray[i] != DELIMITER_END_CHAR) ++i;
        String key = str.substring(begin, i++);
        String replacement = map.get(key);
        if (replacement == null) {
          replacement = VAR_DETECTOR + key + DELIMITER_END_CHAR;
        }
        sb.append(replacement);
      }
      else {
        sb.append(strArray[i]);
        ++i;
      }
    }
    if (i < strArray.length) sb.append(strArray[i]);
    return sb.toString();
  }
}
