package org.elasticsearch.plugin.readonlyrest.utils;

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;

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
}
