package org.elasticsearch.plugin.readonlyrest.utils;

public class Pair<L, R> {

  private final L first;
  private final R second;

  public static <L, R> Pair<L, R> create(L first, R second) {
    return new Pair<>(first, second);
  }

  private Pair(L first, R second) {
    this.first = first;
    this.second = second;
  }

  public L getFirst() {
    return first;
  }

  public R getSecond() {
    return second;
  }
}
