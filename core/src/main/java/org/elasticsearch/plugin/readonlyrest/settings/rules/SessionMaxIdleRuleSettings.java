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
package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.time.Duration;

public class SessionMaxIdleRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "session_max_idle";

  private final Duration maxIdle;

  public static SessionMaxIdleRuleSettings from(String millisStr) {
    Duration duration = Duration.ofMillis(timeIntervalStringToMillis(millisStr));
    if (duration.isNegative() || duration.isZero()) {
      throw new SettingsMalformedException("'" + ATTRIBUTE_NAME + "' req must be greater than zero");
    }
    return new SessionMaxIdleRuleSettings(duration);
  }

  private SessionMaxIdleRuleSettings(Duration maxIdle) {
    this.maxIdle = maxIdle;
  }

  public Duration getMaxIdle() {
    return maxIdle;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }

  private static long timeIntervalStringToMillis(String input) {
    long result = 0;
    String number = "";
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (Character.isDigit(c)) {
        number += c;
      }
      else if (Character.isLetter(c) && !number.isEmpty()) {
        result += convert(Integer.parseInt(number), c);
        number = "";
      }
    }
    return result;
  }

  private static long convert(int value, char unit) {
    switch (unit) {
      case 'y':
        return value * 1000 * 60 * 60 * 24 * 365;
      case 'w':
        return value * 1000 * 60 * 60 * 24 * 7;
      case 'd':
        return value * 1000 * 60 * 60 * 24;
      case 'h':
        return value * 1000 * 60 * 60;
      case 'm':
        return value * 1000 * 60;
      case 's':
        return value * 1000;
      default:
        return 0;
    }
  }

}
