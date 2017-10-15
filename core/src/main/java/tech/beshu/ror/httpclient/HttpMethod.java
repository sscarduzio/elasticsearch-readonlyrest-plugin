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
package tech.beshu.ror.httpclient;

import java.util.Optional;

public enum HttpMethod {
  GET, POST, PUT, DELETE, OPTIONS, HEAD;

  public static Optional<HttpMethod> fromString(String value) {
    switch (value.toLowerCase()) {
      case "get":
        return Optional.of(GET);
      case "post":
        return Optional.of(POST);
      case "put":
        return Optional.of(PUT);
      case "delete":
        return Optional.of(DELETE);
      case "options":
        return Optional.of(OPTIONS);
      case "head":
        return Optional.of(HEAD);
      default:
        return Optional.empty();
    }
  }
}
