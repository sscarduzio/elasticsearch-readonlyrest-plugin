package tech.beshu.ror.commons;

import java.util.Optional;

/**
 * Created by sscarduzio on 25/04/2017.
 */
@Deprecated
public enum Verbosity {
  INFO, ERROR;

  public static Optional<Verbosity> fromString(String value) {
    switch (value.toLowerCase()) {
      case "info":
        return Optional.of(INFO);
      case "error":
        return Optional.of(ERROR);
      default:
        return Optional.empty();
    }
  }
}
