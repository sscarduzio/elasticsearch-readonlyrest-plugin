package org.elasticsearch.plugin.readonlyrest.acl.blocks.domain;

import java.util.function.Function;

public interface Value<T> {

  T getValue(VariableResolver resolver);

  static <T> Value<T> fromString(String value, Function<String, T> creator) {
    return value.contains("@")
        ? new Variable<>(value, creator)
        : new Const<>(creator.apply(value));
  }

  interface VariableResolver {
    String resolveVariable(String original);
  }

  class ResolvingException extends RuntimeException {
    public ResolvingException(String value, String variable) {
      super("'" + value + "' is not correct value for variable '" + variable + "'");
    }
  }
}
