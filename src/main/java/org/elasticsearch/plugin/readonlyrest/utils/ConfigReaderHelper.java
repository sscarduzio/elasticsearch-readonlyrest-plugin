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
package org.elasticsearch.plugin.readonlyrest.utils;

import com.google.common.collect.Lists;
import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ConfigReaderHelper {

  private ConfigReaderHelper() {
  }

  public static String requiredAttributeValue(String attribute, Settings settings) {
    return requiredAttributeValue(attribute, settings, Function.identity());
  }

  public static <T> T requiredAttributeValue(String attribute, Settings settings, Function<String, T> converter) {
    String value = settings.get(attribute);
    if (value == null)
      throw new ConfigMalformedException("Config definition malformed - no value [" + attribute +
          "] attribute");
    return converter.apply(value);
  }

  public static List<String> requiredAttributeArrayValue(String attribute, Settings settings) {
    return requiredAttributeArrayValue(attribute, settings, Function.identity());
  }

  public static <T> List<T> requiredAttributeArrayValue(String attribute, Settings settings, Function<String, T> converter) {
    String[] value = settings.getAsArray(attribute);
    if (value == null)
      throw new ConfigMalformedException("Config definition malformed - no array [" + attribute +
          "] attribute");
    return Lists.newArrayList(value).stream()
                .map(converter)
                .collect(Collectors.toList());
  }

  public static <T> Optional<T> optionalAttributeValue(String attribute, Settings settings, Function<String, T> converter) {
    return Optional.ofNullable(settings.get(attribute)).map(converter);
  }

  public static Function<String, URI> toUri() {
    return value -> {
      try {
        return new URI(value);
      } catch (URISyntaxException e) {
        throw new ConfigMalformedException("Value '" + value + "' cannot be converted to String");
      }
    };
  }

  public static Function<String, Duration> toDuration() {
    return value -> Duration.ofSeconds(Long.parseLong(value));
  }

  public static <R extends Rule> Optional<R> fromSettings(String ruleName,
      Settings settings,
      Function<Settings, Optional<R>> simpleRuleSchemaParser,
      Function<Settings, Optional<R>> extendedRuleSchemaParser)
      throws ConfigMalformedException {
    Optional<RuleSchema> proxyAuthSettingsSchema = recognizeRuleSettingsSchema(settings, ruleName);
    if (!proxyAuthSettingsSchema.isPresent()) return Optional.empty();
    switch (proxyAuthSettingsSchema.get()) {
      case SIMPLE:
        return simpleRuleSchemaParser.apply(settings);
      case EXTENDED:
        return extendedRuleSchemaParser.apply(settings);
      default:
        throw new IllegalStateException("Unknown auth setting schema");
    }
  }

  public static Optional<RuleSchema> recognizeRuleSettingsSchema(Settings s, String ruleName) {
    try {
      return s.getGroups(ruleName).size() > 0
          ? Optional.of(RuleSchema.EXTENDED)
          : checkIsSimpleSchema(s, ruleName);
    } catch (SettingsException ex) {
      return checkIsSimpleSchema(s, ruleName);
    }
  }

  private static Optional<RuleSchema> checkIsSimpleSchema(Settings s, String ruleName) {
    String[] array = s.getAsArray(ruleName);
    if (array != null && array.length > 0) {
      return Optional.of(RuleSchema.SIMPLE);
    }
    else if (s.get(ruleName) != null) {
      return Optional.of(RuleSchema.SIMPLE);
    }
    else {
      return Optional.empty();
    }
  }

  public static Optional<Integer> toInteger(String value) {
    try {
      return Optional.ofNullable(Integer.valueOf(value));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  public static Optional<Boolean> toBoolen(String value) {
    try {
      return Optional.ofNullable(Booleans.parseBooleanExact(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  public static Optional<Duration> toDuration(String value) {
    try {
      return Optional.ofNullable(Long.valueOf(value)).map(Duration::ofSeconds);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  public enum RuleSchema {
    SIMPLE, EXTENDED
  }
}
