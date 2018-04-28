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
package tech.beshu.ror.commons.domain;

import java.util.Optional;
import java.util.function.Function;

public interface Value<T> {

  static <T> Value<T> fromString(String value, Function<String, T> creator) {
    return value.contains("@")
      ? new Variable<>(value, creator)
      : new Const<>(creator.apply(value));
  }

  Optional<T> getValue(VariableResolver resolver);

  interface VariableResolver {
    Optional<String> resolveVariable(String original);
  }

  class ResolvingException extends RuntimeException {
    ResolvingException(String value, String variable) {
      super("'" + value + "' is not correct value for variable '" + variable + "'");
    }
  }
}
