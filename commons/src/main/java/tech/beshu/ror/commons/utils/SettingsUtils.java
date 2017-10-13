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

package tech.beshu.ror.commons.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class SettingsUtils {


  public static Map<String, Object> getAsStructuredMap(Map<String, String> settings) {
    Map<String, Object> map = new HashMap<>(2);
    for (Map.Entry<String, String> entry : settings.entrySet()) {
      processSetting(map, "", entry.getKey(), entry.getValue());
    }
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (entry.getValue() instanceof Map) {
        @SuppressWarnings("unchecked") Map<String, Object> valMap = (Map<String, Object>) entry.getValue();
        entry.setValue(convertMapsToArrays(valMap));
      }
    }

    return map;
  }

  private static void processSetting(Map<String, Object> map, String prefix, String setting, String value) {
    int prefixLength = setting.indexOf('.');
    if (prefixLength == -1) {
      @SuppressWarnings("unchecked") Map<String, Object> innerMap = (Map<String, Object>) map.get(prefix + setting);
      if (innerMap != null) {
        // It supposed to be a value, but we already have a map stored, need to convert this map to "." notation
        for (Map.Entry<String, Object> entry : innerMap.entrySet()) {
          map.put(prefix + setting + "." + entry.getKey(), entry.getValue());
        }
      }
      map.put(prefix + setting, value);
    }
    else {
      String key = setting.substring(0, prefixLength);
      String rest = setting.substring(prefixLength + 1);
      Object existingValue = map.get(prefix + key);
      if (existingValue == null) {
        Map<String, Object> newMap = new HashMap<>(2);
        processSetting(newMap, "", rest, value);
        map.put(key, newMap);
      }
      else {
        if (existingValue instanceof Map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> innerMap = (Map<String, Object>) existingValue;
          processSetting(innerMap, "", rest, value);
          map.put(key, innerMap);
        }
        else {
          // It supposed to be a map, but we already have a value stored, which is not a map
          // fall back to "." notation
          processSetting(map, prefix + key + ".", rest, value);
        }
      }
    }
  }

  private static Object convertMapsToArrays(Map<String, Object> map) {
    if (map.isEmpty()) {
      return map;
    }
    boolean isArray = true;
    int maxIndex = -1;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (isArray) {
        try {
          int index = Integer.parseInt(entry.getKey());
          if (index >= 0) {
            maxIndex = Math.max(maxIndex, index);
          }
          else {
            isArray = false;
          }
        } catch (NumberFormatException ex) {
          isArray = false;
        }
      }
      if (entry.getValue() instanceof Map) {
        @SuppressWarnings("unchecked") Map<String, Object> valMap = (Map<String, Object>) entry.getValue();
        entry.setValue(convertMapsToArrays(valMap));
      }
    }
    if (isArray && (maxIndex + 1) == map.size()) {
      ArrayList<Object> newValue = new ArrayList<>(maxIndex + 1);
      for (int i = 0; i <= maxIndex; i++) {
        Object obj = map.get(Integer.toString(i));
        if (obj == null) {
          // Something went wrong. Different format?
          // Bailout!
          return map;
        }
        newValue.add(obj);
      }
      return newValue;
    }
    return map;
  }
}
