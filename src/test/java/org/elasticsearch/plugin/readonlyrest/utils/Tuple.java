/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package org.elasticsearch.plugin.readonlyrest.utils;

public class Tuple<A, B> {

  private final A v1;
  private final B v2;

  public Tuple(A v1, B v2) {
    this.v1 = v1;
    this.v2 = v2;
  }

  public static <A, B>  Tuple<A, B> from(A v1, B v2) {
    return new Tuple<>(v1, v2);
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
