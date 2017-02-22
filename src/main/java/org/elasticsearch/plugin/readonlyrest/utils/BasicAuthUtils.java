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

import com.google.common.base.Strings;

import java.util.Base64;
import java.util.Map;

public class BasicAuthUtils {

    private BasicAuthUtils() {}

    // Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==
    public static String extractAuthFromHeader(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.trim().length() == 0 || !authorizationHeader.contains("Basic "))
            return null;
        String interestingPart = authorizationHeader.split("Basic")[1].trim();
        if (interestingPart.length() == 0) {
            return null;
        }
        return interestingPart;
    }

    public static String getBasicAuthUser(Map<String, String> headers) {
        String authHeader = extractAuthFromHeader(headers.get("Authorization"));
        if (!Strings.isNullOrEmpty(authHeader)) {
            try {
                String[] splitted = new String(Base64.getDecoder().decode(authHeader)).split(":");
                return splitted.length > 0 ? splitted[0] : null;
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
