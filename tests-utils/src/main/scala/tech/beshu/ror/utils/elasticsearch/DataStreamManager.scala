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
package tech.beshu.ror.utils.elasticsearch

import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPost, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse}
import tech.beshu.ror.utils.httpclient.RestClient

class DataStreamManager(client: RestClient) extends BaseManager(client) {

  def createDataStream(name: String): JsonResponse = {
    val request = new HttpPut(client.from(s"/_data_stream/$name"))
    call(request, new JsonResponse(_))
  }

  def addDocument(name: String, body: JSON): JsonResponse = {
    val request = new HttpPost(client.from(s"$name/_doc"))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(body)))
    call(request, new JsonResponse(_))
  }

  def getAllDataStreams(): JsonResponse = {
    val request = new HttpGet(client.from(s"/_data_stream/"))
    call(request, new JsonResponse(_))
  }

  def getDataStream(name: String): JsonResponse = {
    val request = new HttpGet(client.from(s"/_data_stream/$name"))
    call(request, new JsonResponse(_))
  }

  def getDataStreamStats(name: String): JsonResponse = {
    val request = new HttpGet(client.from(s"/_data_stream/$name/_stats"))
    call(request, new JsonResponse(_))
  }

  def deleteDataStream(name: String): JsonResponse = {
    val request = new HttpDelete(client.from(s"/_data_stream/$name"))
    call(request, new JsonResponse(_))
  }

  def rollover(name: String): JsonResponse = {
    val request = new HttpPost(client.from(s"/$name/_rollover/"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(""))
    call(request, new JsonResponse(_))
  }
}
