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

package tech.beshu.ror;

import java.util.Optional;

/**
 * Created by sscarduzio on 25/04/2017.
 */
public enum Verbosity {
  INFO, ERROR;

  public static Optional<Verbosity> fromString(String value) {
    switch (value.toLowerCase()) {
      case "info":
        return Optional.of(INFO);
      case "error":
        return Optional.of(ERROR);
      default:
        return Optional.empty();
    }
  }
}
