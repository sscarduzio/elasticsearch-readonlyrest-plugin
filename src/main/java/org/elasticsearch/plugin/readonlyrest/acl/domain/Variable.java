package org.elasticsearch.plugin.readonlyrest.acl.domain;

import java.util.Optional;
import java.util.function.Function;

public class Variable<T> implements Value<T> {

  private final String value;
  private final Function<String, T> creator;

  Variable(String value, Function<String, T> creator) {
    this.value = value;
    this.creator = creator;
  }

  @Override
  public Optional<T> getValue(VariableResolver resolver) {
    Optional<String> resolved = resolver.resolveVariable(value);
    try {
      return resolved.map(creator);
    } catch (Exception ex) {
      throw new ResolvingException(resolved.orElse("[unresolved]"), value);
    }
  }
}
