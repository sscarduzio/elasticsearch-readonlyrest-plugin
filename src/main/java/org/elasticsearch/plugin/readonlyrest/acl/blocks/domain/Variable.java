package org.elasticsearch.plugin.readonlyrest.acl.blocks.domain;

import java.util.function.Function;

public class Variable<T> implements Value<T> {

  private final String value;
  private final Function<String, T> creator;

  Variable(String value, Function<String, T> creator) {
    this.value = value;
    this.creator = creator;
  }

  @Override
  public T getValue(VariableResolver resolver) {
    String resolved = resolver.resolveVariable(value);
    try {
      return creator.apply(resolved);
    } catch (Exception ex) {
      throw new ResolvingException(resolved, value);
    }
  }
}
