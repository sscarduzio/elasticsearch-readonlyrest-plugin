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
import org.apache.http.client.methods.HttpGet
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse}
import tech.beshu.ror.utils.elasticsearch.CatManager.{CatNodesResponse, CatResponse, CatShardsResponse}
import tech.beshu.ror.utils.httpclient.RestClient
import ujson.{Arr, Value}

import scala.annotation.nowarn

class CatManager(client: RestClient,
                 override val additionalHeaders: Map[String, String] = Map.empty,
                 @nowarn("cat=unused") esVersion: String)
  extends BaseManager(client) {

  def main(): JsonResponse = call(new HttpGet(client.from("/")), new JsonResponse(_))

  def cat(): JsonResponse = call(genericCatRequest(""), new JsonResponse(_))

  def healthCheck(): JsonResponse = call(genericCatRequest("health"), new JsonResponse(_))

  def templates(): CatResponse = call(createCatTemplatesRequest(None), new CatResponse(_))

  def templates(index: String): CatResponse = call(createCatTemplatesRequest(Some(index)), new CatResponse(_))

  def indices(): CatResponse = call(createCatIndicesRequest(None), new CatResponse(_))

  def indices(index: String): CatResponse = call(createCatIndicesRequest(Some(index)), new CatResponse(_))

  def aliases(): CatResponse = call(genericCatRequest("aliases"), new CatResponse(_))

  def tasks(): CatResponse = call(genericCatRequest("tasks"), new CatResponse(_))

  def nodes(): CatNodesResponse = call(genericCatRequest("nodes"), new CatNodesResponse(_))

  def shards(): CatShardsResponse = call(genericCatRequest("shards"), new CatShardsResponse(_))

  private def createCatTemplatesRequest(index: Option[String]) = {
    new HttpGet(client.from(
      s"/_cat/templates${index.map(i => s"/$i").getOrElse("")}",
      Map("format" -> "json")
    ))
  }

  private def createCatIndicesRequest(index: Option[String]) = {
    new HttpGet(client.from(
      s"/_cat/indices${index.map(i => s"/$i").getOrElse("")}",
      Map("format" -> "json", "s" -> "index:asc")
    ))
  }

  private def genericCatRequest(catType: String) = {
    new HttpGet(client.from(s"/_cat/$catType", Map("format" -> "json")))
  }

}

object CatManager {

  sealed class CatResponse(response: HttpResponse)
    extends JsonResponse(response) {

    lazy val results: Vector[Value] = responseJson match {
      case Arr(value) => value.toVector
      case value => throw new AssertionError(s"Expecting JSON list, got: $value")
    }
  }

  final class CatShardsResponse(response: HttpResponse)
    extends CatResponse(response) {

    def nodeOfIndex(index: String): Option[String] = {
      ofIndex(index)
        .map(_("node").str)
        .toList.headOption
    }

    def shardOfIndex(index: String): Option[String] = {
      ofIndex(index)
        .map(_("shard").str)
        .toList.headOption
    }

    def ofIndex(index: String): Option[JSON] = {
      responseJson.arr.find(i => i("index").str == index)
    }
  }

  final class CatNodesResponse(response: HttpResponse)
    extends CatResponse(response) {

    def names: List[String] = {
      responseJson.arr.map(_("name").str).toList.distinct
    }
  }

  final class SingleLineCatResponse(response: HttpResponse)
    extends JsonResponse(response) {

    lazy val result: Value = responseJson match {
      case Arr(value) =>
        value.toVector.toList match {
          case Nil => throw new AssertionError(s"Expecting one element JSON list, got: $value")
          case one :: Nil => one
          case _ => throw new AssertionError(s"Expecting one element JSON list, got: $value")
        }
      case value => throw new AssertionError(s"Expecting JSON list, got: $value")
    }
  }
}