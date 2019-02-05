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
