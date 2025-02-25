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

import org.apache.http.HttpResponse
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPost, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.httpclient.RestClient

class DataStreamManager(client: RestClient, esVersion: String)
  extends BaseManager(client, esVersion, esNativeApi = true) {

  def createDataStream(name: String): JsonResponse = {
    val request = new HttpPut(client.from(s"/_data_stream/$name"))
    call(request, new JsonResponse(_))
  }

  def getAllDataStreams(): GetAllDataStreamsResult = {
    val request = new HttpGet(client.from(s"/_data_stream/"))
    call(request, new GetAllDataStreamsResult(_))
  }

  def getDataStream(name: String): GetDataStreamResult = {
    val request = new HttpGet(client.from(s"/_data_stream/$name"))
    call(request, new GetDataStreamResult(_))
  }

  def getDataStreamStats(name: String): DataStreamStatsResult = {
    val request = new HttpGet(client.from(s"/_data_stream/$name/_stats"))
    call(request, new DataStreamStatsResult(_))
  }

  def deleteDataStream(name: String): JsonResponse = {
    val request = new HttpDelete(client.from(s"/_data_stream/$name"))
    call(request, new JsonResponse(_))
  }

  def migrateToDataStream(aliasName: String): JsonResponse = {
    val request = new HttpPost(client.from(s"/_data_stream/_migrate/$aliasName"))
    call(request, new JsonResponse(_))
  }

  def modifyDataStreams(body: JSON): JsonResponse = {
    val request = new HttpPost(client.from(s"/_data_stream/_modify"))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(body)))
    call(request, new JsonResponse(_))
  }

  class GetAllDataStreamsResult(response: HttpResponse) extends JsonResponse(response) {

    def allBackingIndices: List[String] = {
      dataStreamWithBackingIndices.values.flatten.toList
    }

    def allDataStreams: List[String] = {
      dataStreamWithBackingIndices.keys.toList
    }

    def backingIndicesByDataStream(dataStream: String): List[String] = {
      dataStreamWithBackingIndices(dataStream)
    }

    def ilmPolicyByDataStream(dataStream: String): String = {
      dataStreamWithIlmPolicy(dataStream)
    }

    def indexTemplateByDataStream(dataStream: String): String = {
      dataStreamWithTemplate(dataStream)
    }

    private lazy val dataStreamWithBackingIndices: Map[String, List[String]] =
      responseJson("data_streams").arr
        .map { dataStream =>
          (
            dataStream("name").str,
            dataStream("indices").arr.map(_("index_name").str).toList
          )
        }
        .toMap

    private lazy val dataStreamWithTemplate: Map[String, String] =
      responseJson("data_streams").arr
        .map { dataStream =>
          (
            dataStream("name").str,
            dataStream("template").str
          )
        }
        .toMap

    private lazy val dataStreamWithIlmPolicy: Map[String, String] =
      responseJson("data_streams").arr
        .map { dataStream =>
          (
            dataStream("name").str,
            dataStream("ilm_policy").str
          )
        }
        .toMap
  }

  class GetDataStreamResult(response: HttpResponse) extends GetAllDataStreamsResult(response) {

    def dataStreamName: String = allDataStreams.head

    def backingIndices: List[String] = allBackingIndices
  }

  class DataStreamStatsResult(response: HttpResponse) extends JsonResponse(response) {

    def dataStreamsCount: Int = responseJson("data_stream_count").num.toInt

    def backingIndicesCount: Int = responseJson("backing_indices").num.toInt
  }
}

