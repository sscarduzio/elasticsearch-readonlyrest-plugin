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
