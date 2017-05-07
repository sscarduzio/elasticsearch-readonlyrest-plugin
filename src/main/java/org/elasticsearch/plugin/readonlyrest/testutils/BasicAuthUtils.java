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

package org.elasticsearch.plugin.readonlyrest.testutils;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

public class BasicAuthUtils {

  private BasicAuthUtils() {
  }

  public static Header basicAuthHeader(String user, String password) {
    return new BasicHeader(
        "Authorization",
        String.format("Basic %s", Base64.getEncoder().encodeToString(String.format("%s:%s", user, password).getBytes())));
  }

  public static Optional<BasicAuth> getBasicAuthFromHeaders(Map<String, String> headers) {
    return Optional.ofNullable(headers.get("Authorization"))
            .flatMap(BasicAuthUtils::getInterestingPartOfBasicAuthValue)
            .flatMap(BasicAuth::fromBase64Value);
  }

  private static Optional<String> getInterestingPartOfBasicAuthValue(String basicAuthValue) {
    if (basicAuthValue == null || basicAuthValue.trim().length() == 0 || !basicAuthValue.contains("Basic ")) {
      return Optional.empty();
    }
    else {
      String[] parts = basicAuthValue.split("Basic");
      if (parts.length == 2) {
        String interestingPart = parts[1].trim();
        return interestingPart.length() > 0 ? Optional.of(interestingPart) : Optional.empty();
      }
      else {
        return Optional.empty();
      }
    }
  }

  public static class BasicAuth {
    private final String base64Value;
    private final String userName;
    private final String password;

    private BasicAuth(String base64Value) {
      this.base64Value = base64Value;
      String[] splitted = new String(Base64.getDecoder().decode(base64Value)).split(":");
      if (splitted.length != 2) {
        throw new IllegalArgumentException("Cannot extract user name from base auth header");
      }
      this.userName = splitted[0];
      this.password = splitted[1];
    }

    public static Optional<BasicAuth> fromBase64Value(String base64Value) {
      try {
        return Optional.of(new BasicAuth(base64Value));
      } catch (IllegalArgumentException ex) {
        return Optional.empty();
      }
    }

    public String getBase64Value() {
      return base64Value;
    }

    public String getUserName() {
      return userName;
    }

    public String getPassword() {
      return password;
    }
  }
}
