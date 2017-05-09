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
package org.elasticsearch.plugin.readonlyrest.httpclient;

import java.io.InputStream;

public class HttpResponse {

  private final int statusCode;
  private final InputStream content;

  public HttpResponse(int statusCode, InputStream content) {
    this.statusCode = statusCode;
    this.content = content;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public InputStream getContent() {
    return content;
  }
}
