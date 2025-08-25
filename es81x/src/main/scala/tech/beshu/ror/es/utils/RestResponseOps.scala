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
import tech.beshu.ror.utils.JsonReader.ujsonRead
import ujson.Value

import scala.util.Try

object RestResponseOps {
  extension (response: Response) {
    def isSuccess: Boolean = statusCode / 100 == 2

    def statusCode: Int = response.getStatusLine.getStatusCode

    def entityJson: Value = {
      ujsonRead(entityStr)
    }

    def entityStr: String = {
      EntityUtils.toString(response.getEntity)
    }

    def errorType: Option[String] = Try(entityJson("error")("type").str).toOption
  }

  extension (request: Request) {
    def setJsonBody(json: Value): Unit = {
      request.setJsonEntity(ujson.write(json))
    }
  }
}
