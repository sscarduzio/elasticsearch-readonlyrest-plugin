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
package tech.beshu.ror.es.utils

import org.apache.http.util.EntityUtils
import org.elasticsearch.client.{Request, Response}
import ujson.Value

object RestResponseOps {
  extension (response: Response) {
    def statusCode: Int = response.getStatusLine.getStatusCode

    def entityJson: Value = {
      val jsonStr = EntityUtils.toString(response.getEntity)
      ujson.read(jsonStr)
    }
  }

  extension (request: Request) {
    def setJsonBody(json: Value): Unit = {
      request.setJsonEntity(ujson.write(json))
    }
  }
}
