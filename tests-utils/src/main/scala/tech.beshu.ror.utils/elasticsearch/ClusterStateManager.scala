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
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JsonResponse, SimpleResponse}
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager.CatResponse
import tech.beshu.ror.utils.httpclient.RestClient
import ujson.{Arr, Value}

import scala.collection.JavaConverters._

class ClusterStateManager(client: RestClient)
  extends BaseManager(client) {

  def healthCheck(): SimpleResponse = {
    call(new HttpGet(client.from("/_cat/health")), new SimpleResponse(_))
  }

  def catTemplates(): CatResponse = {
    call(createCatTemplatesRequest(None), new CatResponse(_))
  }

  def catTemplates(index: String): CatResponse = {
    call(createCatTemplatesRequest(Some(index)), new CatResponse(_))
  }

  def catIndices(): CatResponse = {
    call(createCatIndicesRequest(None), new CatResponse(_))
  }

  def catIndices(index: String): CatResponse = {
    call(createCatIndicesRequest(Some(index)), new CatResponse(_))
  }

  private def createCatTemplatesRequest(index: Option[String]) = {
    new HttpGet(client.from(
      s"/_cat/templates${index.map(i => s"/$i").getOrElse("")}",
      Map("format" -> "json").asJava
    ))
  }

  private def createCatIndicesRequest(index: Option[String]) = {
    new HttpGet(client.from(
      s"/_cat/indices${index.map(i => s"/$i").getOrElse("")}",
      Map("format" -> "json").asJava
    ))
  }
}

object ClusterStateManager {

  final class CatResponse(response: HttpResponse)
    extends JsonResponse(response) {

    lazy val results: Vector[Value] = responseJson match {
      case Arr(value) => value.toVector
      case value => throw new AssertionError(s"Expecting JSON list, got: $value")
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