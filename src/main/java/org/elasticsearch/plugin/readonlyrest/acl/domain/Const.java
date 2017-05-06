package org.elasticsearch.plugin.readonlyrest.acl.domain;

public class Const<T> implements Value<T> {

  private final T value;

  Const(T value) {
    this.value = value;
  }

  @Override
  public T getValue(VariableResolver resolver) {
    return value;
  }
}
