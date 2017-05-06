package org.elasticsearch.plugin.readonlyrest.utils;

public class Tuple<A, B> {

  private final A v1;
  private final B v2;

  public Tuple(A v1, B v2) {
    this.v1 = v1;
    this.v2 = v2;
  }

  public A v1() {
    return v1;
  }

  public B v2() {
    return v2;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    Tuple<?, ?> tuple = (Tuple<?, ?>) o;
    if (!v1.equals(tuple.v1)) return false;
    return v2.equals(tuple.v2);
  }

  @Override
  public int hashCode() {
    int result = v1.hashCode();
    result = 31 * result + v2.hashCode();
    return result;
  }
}
