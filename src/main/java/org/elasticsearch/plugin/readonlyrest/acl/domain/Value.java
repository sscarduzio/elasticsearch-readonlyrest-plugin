package org.elasticsearch.plugin.readonlyrest.acl.domain;

import java.util.Optional;
import java.util.function.Function;

public interface Value<T> {

  Optional<T> getValue(VariableResolver resolver);

  static <T> Value<T> fromString(String value, Function<String, T> creator) {
    return value.contains("@")
        ? new Variable<>(value, creator)
        : new Const<>(creator.apply(value));
  }

  interface VariableResolver {
    Optional<String> resolveVariable(String original);
  }

  class ResolvingException extends RuntimeException {
    ResolvingException(String value, String variable) {
      super("'" + value + "' is not correct value for variable '" + variable + "'");
    }
  }
}
