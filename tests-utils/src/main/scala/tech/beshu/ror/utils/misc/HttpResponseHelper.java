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
package tech.beshu.ror.utils.misc;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class HttpResponseHelper {

  public static Map<String, Object> deserializeJsonBody(String response) {
    Gson gson = new Gson();
    Type mapType = new TypeToken<HashMap<String, Object>>(){}.getType();
    return gson.fromJson(response, mapType);
  }

  public static String stringBodyFrom(HttpResponse response) {
    try {
      return EntityUtils.toString(response.getEntity());
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot convert body to string", ex);
    }
  }
}
