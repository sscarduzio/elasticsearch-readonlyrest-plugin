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

package tech.beshu.ror.utils;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

public class BasicAuthUtils {

  private BasicAuthUtils() {
  }

  public static String basicAuthHeaderValue(String user, String password) {
    return String.format("Basic %s", Base64.getEncoder().encodeToString(String.format("%s:%s", user, password).getBytes()));
  }

  public static Optional<BasicAuth> getBasicAuthFromHeaders(Map<String, String> headers) {
    return Optional.ofNullable(headers.get("Authorization"))
      .flatMap(BasicAuthUtils::getInterestingPartOfBasicAuthValue)
      .flatMap(BasicAuth::fromBase64Value);
  }
  
  public static Optional<BasicAuth> getBasicAuthFromString(String base64Value) {
	  return BasicAuth.fromBase64Value(base64Value);
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
      String decoded = new String(Base64.getDecoder().decode(base64Value));
      int index = decoded.indexOf(":");
      if (index == -1 || index == decoded.length() - 1) {
        throw new IllegalArgumentException("Cannot extract user name from base auth header");
      }
      this.userName = decoded.substring(0,index);
      this.password = decoded.substring(index + 1);
    }

    public static Optional<BasicAuth> fromBase64Value(String base64Value) {
        return Optional.of(new BasicAuth(base64Value));
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
