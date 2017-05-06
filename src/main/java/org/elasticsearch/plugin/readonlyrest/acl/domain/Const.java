package org.elasticsearch.plugin.readonlyrest.acl.domain;

import java.util.Optional;

public class Const<T> implements Value<T> {

  private final T value;

  Const(T value) {
    this.value = value;
  }

  @Override
  public Optional<T> getValue(VariableResolver resolver) {
    return Optional.of(value);
  }
}
