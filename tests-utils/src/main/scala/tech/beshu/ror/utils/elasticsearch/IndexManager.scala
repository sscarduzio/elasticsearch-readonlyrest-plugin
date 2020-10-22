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

import cats.data.NonEmptyList
import org.apache.http.HttpResponse
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPost, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JsonResponse, SimpleResponse}
import tech.beshu.ror.utils.elasticsearch.IndexManager.{AliasAction, AliasesResponse, ResolveResponse}
import tech.beshu.ror.utils.httpclient.RestClient

class IndexManager(client: RestClient,
                   override val additionalHeaders: Map[String, String] = Map.empty)
  extends BaseManager(client) {

  def getIndex(indices: List[String], params:Map[String,String]): JsonResponse = {
    call(getIndexRequest(indices.toSet, params), new JsonResponse(_))
  }

  def getIndex(indices: String*): JsonResponse = {
    call(getIndexRequest(indices.toSet, Map.empty), new JsonResponse(_))
  }

  def getAliases: AliasesResponse = {
    call(getAliasRequest(), new AliasesResponse(_))
  }

  def getAlias(indices: String*): AliasesResponse = {
    val indexOpt = indices.mkString(",") match {
      case "" => None
      case str => Some(str)
    }
    call(getAliasRequest(indexOpt), new AliasesResponse(_))
  }

  def getAliasByName(index: String, alias: String): AliasesResponse = {
    call(getAliasRequest(Some(index), Some(alias)), new AliasesResponse(_))
  }

  def createAliasOf(index: String, alias: String): JsonResponse = {
    call(createAliasRequest(index, alias), new JsonResponse(_))
  }

  def deleteAliasOf(index: String, alias: String): JsonResponse = {
    call(deleteAliasRequest(index, alias), new JsonResponse(_))
  }

  def updateAliases(action: AliasAction, actions: AliasAction*): JsonResponse = {
    call(updateAliasesRequest(NonEmptyList.of(action, actions: _*)), new JsonResponse(_))
  }

  def getSettings(index: String*): JsonResponse = {
    call(createGetSettingsRequest(index.toSet), new JsonResponse(_))
  }

  def getAllSettings: JsonResponse = {
    call(createGetSettingsRequest(Set("_all")), new JsonResponse(_))
  }

  def putAllSettings(numberOfReplicas: Int): JsonResponse = {
    call(createPutAllSettingsRequest(numberOfReplicas), new JsonResponse(_))
  }

  def putSettings(indexName: String, allocationNodeNames: String*): JsonResponse = {
    call(createPutSettingsRequest(indexName, allocationNodeNames.toList), new JsonResponse(_))
  }

  def removeAllIndices(): SimpleResponse =
    call(createDeleteAllIndicesRequest, new SimpleResponse(_))

  def removeIndex(indexName: String): SimpleResponse =
    call(createDeleteIndexRequest(indexName), new SimpleResponse(_))

  def removeAllAliases(): SimpleResponse =
    call(createDeleteAliasesRequest, new SimpleResponse(_))

  def getMapping(indexName: String, field: String): JsonResponse = {
    call(createGetMappingRequest(indexName, field), new JsonResponse(_))
  }

  def rollover(target: String, index: String): JsonResponse = {
    call(createRolloverRequest(target, Some(index)), new JsonResponse(_))
  }

  def rollover(target: String): JsonResponse = {
    call(createRolloverRequest(target, None), new JsonResponse(_))
  }

  def resolve(indexPattern: String, otherIndexPatterns: String*): ResolveResponse = {
    call(createResolveRequest(indexPattern :: otherIndexPatterns.toList), new ResolveResponse(_))
  }

  private def getAliasRequest(indexOpt: Option[String] = None,
                              aliasOpt: Option[String] = None) = {
    val path = indexOpt match {
      case Some(index) =>
        aliasOpt match {
          case Some(alias) => s"$index/_alias/$alias"
          case None => s"$index/_alias"
        }
      case None =>
        aliasOpt match {
          case Some(alias) => s"_alias/$alias"
          case None => "_alias"
        }
    }
    new HttpGet(client.from(path))
  }

  private def getIndexRequest(indices: Set[String], params:Map[String,String]) = {
    import scala.collection.JavaConverters._
    new HttpGet(client.from(indices.mkString(","), params.asJava))
  }

  private def createAliasRequest(index: String, alias: String) = {
    new HttpPut(client.from(s"/$index/_alias/$alias"))
  }

  private def deleteAliasRequest(index: String, alias: String) = {
    new HttpDelete(client.from(s"$index/_aliases/$alias"))
  }

  private def createGetSettingsRequest(indices: Set[String]) = {
    new HttpGet(client.from(s"${indices.mkString(",")}/_settings"))
  }

  private def createDeleteAllIndicesRequest = createDeleteIndexRequest("_all")

  private def createDeleteIndexRequest(indexName: String) = {
    new HttpDelete(client.from(s"/$indexName"))
  }

  private def createDeleteAliasesRequest = {
    new HttpDelete(client.from("/_all/_aliases/_all"))
  }

  private def createPutAllSettingsRequest(numberOfReplicas: Int) = {
    val request = new HttpPut(client.from("/_all/_settings/"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""{"index":{"number_of_replicas":$numberOfReplicas}}"""
    ))
    request
  }

  private def createPutSettingsRequest(indexName: String, allocationNodeNames: List[String]) = {
    val request = new HttpPut(client.from(s"/$indexName/_settings/"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""{"index.routing.allocation.include._name":"${allocationNodeNames.mkString(",")}"}"""
    ))
    request
  }

  private def createGetMappingRequest(indexName: String, field: String) = {
    new HttpGet(client.from(s"/$indexName/_mapping/field/$field"))
  }

  private def createRolloverRequest(target: String, index: Option[String]) = {
    val request = new HttpPost(client.from(s"/$target/_rollover/${index.getOrElse("")}"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(""))
    request
  }

  private def updateAliasesRequest(actions: NonEmptyList[AliasAction]) = {
    def actionStrings = actions.map {
      case AliasAction.Add(index, alias) => s"""{ "add": { "index": "$index", "alias": "$alias" } }"""
      case AliasAction.Delete(index, alias) => s"""{ "remove": { "index": "$index", "alias": "$alias" } }"""
    }
    val request = new HttpPost(client.from("/_aliases"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""{
        |  "actions": [
        |     ${actionStrings.toList.mkString(",\n")}
        |  ]
        |}""".stripMargin))
    request
  }

  private def createResolveRequest(indicesPatterns: List[String]) = {
    new HttpGet(client.from(s"/_resolve/index/${indicesPatterns.mkString(",")}"))
  }
}

object IndexManager {

  sealed trait AliasAction
  object AliasAction {
    final case class Add(index: String, alias: String) extends AliasAction
    final case class Delete(index: String, alias: String) extends AliasAction
  }

  class AliasesResponse(response: HttpResponse) extends JsonResponse(response) {
    lazy val aliasesOfIndices: Map[String, List[String]] =
      responseJson.obj.toMap.map { case (indexName, json) =>
        val aliases = json("aliases").obj.keys.toList
        (indexName, aliases)
      }
  }

  class ResolveResponse(response: HttpResponse) extends JsonResponse(response) {
    lazy val indices: List[IndexDescription] =
      responseJson
        .obj.toMap.get("indices")
        .map(_.arr.toList).getOrElse(List.empty)
        .map { vJson =>
          IndexDescription(
            vJson("name").str,
            vJson.obj.get("aliases").map(_.arr.toList).getOrElse(List.empty).map(_.str),
            vJson.obj.get("attributes").map(_.arr.toList).getOrElse(List.empty).map(_.str)
          )
        }

    lazy val aliases: List[AliasDescription] =
      responseJson
        .obj.toMap.get("aliases")
        .map(_.arr.toList).getOrElse(List.empty)
        .map { vJson =>
          AliasDescription(
            vJson("name").str,
            vJson.obj.get("indices").map(_.arr.toList).getOrElse(List.empty).map(_.str)
          )
        }

    sealed case class IndexDescription(name: String, aliases: List[String], attributes: List[String])
    sealed case class AliasDescription(name: String, indices: List[String])
  }
}