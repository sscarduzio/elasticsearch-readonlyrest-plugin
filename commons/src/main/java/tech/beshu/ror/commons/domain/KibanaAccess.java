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

public enum KibanaAccess {
  RO, RW, RO_STRICT, ADMIN;

  public static Optional<KibanaAccess> fromString(String value) {
    switch (value.toLowerCase()) {
      case "ro_strict":
        return Optional.of(RO_STRICT);
      case "ro":
        return Optional.of(RO);
      case "rw":
        return Optional.of(RW);
      case "admin":
        return Optional.of(ADMIN);
      default:
        return Optional.empty();
    }
  }
}
