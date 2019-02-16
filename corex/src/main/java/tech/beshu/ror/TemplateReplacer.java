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
package tech.beshu.ror;

import java.util.Map;

public class TemplateReplacer {

  private final Character escapeChar;
  private final Character delimiterBeginChar;
  private final Character delimiterEndChar;

  public TemplateReplacer(Character escapeChar, Character delimiterBeginChar, Character delimiterEndChar) {
    this.escapeChar = escapeChar;
    this.delimiterBeginChar = delimiterBeginChar;
    this.delimiterEndChar = delimiterEndChar;
  }

  /**
   * Uber-fast regex-free template replacer
   *
   * @param map replacements pool
   * @param str haystack string
   * @return replaced or unchanged string.
   */
  public String replace(Map<String, String> map, String str) {
    StringBuilder sb = new StringBuilder();
    char[] strArray = str.toCharArray();
    int i = 0;
    while (i < strArray.length - 1) {
      if (strArray[i] == escapeChar && strArray[i + 1] == delimiterBeginChar) {
        i = i + 2;
        int begin = i;
        while (strArray[i] != delimiterEndChar)
          ++i;
        String key = str.substring(begin, i++);
        String replacement = map.get(key);
        if (replacement == null) {
          replacement = String.valueOf(escapeChar) + delimiterBeginChar + key + delimiterEndChar;
        }
        sb.append(replacement);
      }
      else {
        sb.append(strArray[i]);
        ++i;
      }
    }
    if (i < strArray.length)
      sb.append(strArray[i]);
    return sb.toString();
  }
}
