package org.elasticsearch.plugin.readonlyrest.settings;

import java.util.Optional;

public class SettingsUtils {

  @SuppressWarnings("unchecked")
  public static <T> Optional<T> of(Object obj) {
    return Optional.ofNullable((T)obj);
  }
}
