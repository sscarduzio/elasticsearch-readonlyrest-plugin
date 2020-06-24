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
import org.apache.http.client.methods.{HttpGet, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JsonResponse, SimpleResponse}
import tech.beshu.ror.utils.elasticsearch.CatManager.CatResponse
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Version
import ujson.{Arr, Value}

import scala.collection.JavaConverters._

class CatManager(client: RestClient,
                 override val additionalHeaders: Map[String, String] = Map.empty,
                 esVersion: String)
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

  def configureRemoteClusters(remoteClusters: Map[String, List[String]]): SimpleResponse = {
    call(createAddCLusterSettingsRequest(remoteClusters), new SimpleResponse(_))
  }

  def tasks(): CatResponse = {
    call(createTasksRequest(), new CatResponse(_))
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
      Map("format" -> "json", "s" -> "index:asc").asJava
    ))
  }

  private def createTasksRequest() = {
    new HttpGet(client.from(s"/_cat/tasks", Map("format" -> "json").asJava))
  }

  private def createAddCLusterSettingsRequest(remoteClusters: Map[String, List[String]]) = {
    val remoteClustersConfigString = remoteClusters
      .map { case (name, seeds) =>
        s""""$name": { "seeds": [ ${seeds.mkString(",")} ] }"""
      }
      .mkString(",\n")

    val request = new HttpPut(client.from("_cluster/settings"))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      if (Version.greaterOrEqualThan(esVersion, 6, 5, 0)) {
        s"""
           |{
           |  "persistent": {
           |    "cluster": {
           |      "remote": {
           |        $remoteClustersConfigString
           |      }
           |    }
           |  }
           |}
          """.stripMargin
      } else {
        s"""
           |{
           |  "persistent": {
           |    "search": {
           |      "remote": {
           |        $remoteClustersConfigString
           |      }
           |    }
           |  }
           |}
          """.stripMargin
      }
    ))
    request
  }
}

object CatManager {

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