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
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.IndexManager.*
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Version

class IndexManager(client: RestClient,
                   esVersion: String,
                   override val additionalHeaders: Map[String, String] = Map.empty)
  extends BaseManager(client, esVersion, esNativeApi = true) {

  def createIndex(index: String,
                  settings: Option[JSON] = None,
                  params: Map[String, String] = Map.empty): JsonResponse = {
    call(createIndexRequest(index, settings, params), new JsonResponse(_))
  }

  def getIndex(indices: List[String], params: Map[String, String] = Map.empty): GetIndexResponse = {
    call(getIndexRequest(indices.toSet, params), new GetIndexResponse(_))
  }

  def getIndex(indices: String*): GetIndexResponse = {
    call(getIndexRequest(indices.toSet, Map.empty), new GetIndexResponse(_))
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

  def putSettings(indexName: String, settings: JSON): SimpleResponse = {
    call(createPutSettingsRequest(indexName, settings), new SimpleResponse(_))
  }

  def putAllSettings(numberOfReplicas: Int): SimpleResponse = {
    putSettings(
      "_all",
      ujson.read(
        s"""{
           |  "index":{
           |    "number_of_replicas":$numberOfReplicas
           |  }
           |}""".stripMargin
      )
    )
  }

  def putSettings(indexName: String, allocationNodeNames: String*): SimpleResponse = {
    putSettings(
      indexName = indexName,
      settings = ujson.read(
        s"""{
           |  "index.routing.allocation.include._name":"${allocationNodeNames.mkString(",")}"
           |}""".stripMargin)
    )
  }

  def putSettingsIndexBlocksWrite(indexName: String, indexBlockWrite: Boolean): SimpleResponse = {
    putSettings(
      indexName = indexName,
      settings = ujson.read(
        s"""{
           |  "settings":{
           |    "index.blocks.write": $indexBlockWrite
           |  }
           |}""".stripMargin)
    )
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

  def createIndexWithMapping(indexName: String, propertiesJson: JSON): JsonResponse = {
    call(createIndexRequest(indexName, None, Map.empty), new JsonResponse(_)).force()
    call(createPutMappingRequest(indexName, propertiesJson), new JsonResponse(_))
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

  def reindex(source: ReindexSource, destIndexName: String): JsonResponse = {
    val request = createReindexRequest(source, destIndexName)
    call(request, new JsonResponse(_, Some(request)))
  }

  def shrink(sourceIndex: String, targetIndex: String, aliases: List[String] = Nil): JsonResponse = {
    call(createShrinkRequest(sourceIndex, targetIndex, aliases), new JsonResponse(_))
  }

  def split(sourceIndex: String, targetIndex: String, numOfShards: Int): JsonResponse = {
    call(createSplitRequest(sourceIndex, targetIndex, numOfShards), new JsonResponse(_))
  }

  def closeIndex(indexName: String): JsonResponse = {
    call(createCloseIndexRequest(indexName), new JsonResponse(_))
  }

  def stats(indexNames: String*): StatsResponse = {
    call(createStatsRequest(indexNames), new StatsResponse(_))
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

  private def getIndexRequest(indices: Set[String], params: Map[String, String]) = {
    new HttpGet(client.from(indices.mkString(","), params))
  }

  private def createIndexRequest(indices: String, settings: Option[JSON], params: Map[String, String]) = {
    val request = new HttpPut(client.from(indices, params))
    settings match {
      case Some(s) =>
        request.addHeader("Content-Type", "application/json")
        request.setEntity(new StringEntity(ujson.write(s)))
      case None =>
    }
    request
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

  private def createPutSettingsRequest(indexName: String, settings: JSON) = {
    val request = new HttpPut(client.from(s"/$indexName/_settings/"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(settings)))
    request
  }

  private def createGetMappingRequest(indexName: String, field: String) = {
    new HttpGet(client.from(s"/$indexName/_mapping/field/$field"))
  }

  private def createPutMappingRequest(indexName: String, propertiesJson: JSON) = {
    val request = new HttpPut(client.from(s"/$indexName/_mapping"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""{
         |  "properties": ${ujson.write(propertiesJson)}
         |}""".stripMargin))
    request
  }

  private def createRolloverRequest(target: String, index: Option[String]) = {
    val request = new HttpPost(client.from(s"/$target/_rollover/${index.getOrElse("")}"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(""))
    request
  }

  private def updateAliasesRequest(actions: NonEmptyList[AliasAction]) = {
    def actionStrings = actions.map {
      case AliasAction.Add(index, alias, None) => s"""{ "add": { "index": "$index", "alias": "$alias" } }"""
      case AliasAction.Add(index, alias, Some(filter)) => s"""{ "add": { "index": "$index", "alias": "$alias", "filter": ${ujson.write(filter)} } }"""
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

  private def createShrinkRequest(sourceIndex: String, targetIndex: String, aliases: List[String]): HttpPost = {
    val parsedAliases = aliases.map(alias => s""""$alias": {}""").mkString(",")
    val request = new HttpPost(client.from(s"/$sourceIndex/_shrink/$targetIndex"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""
         |{
         |	"aliases": {
         |		$parsedAliases
         |	}
         |}""".stripMargin))
    request
  }

  private def createSplitRequest(sourceIndex: String, targetIndex: String, numOfShards: Int) = {
    val request = new HttpPost(client.from(s"/$sourceIndex/_split/$targetIndex"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""
         |{
         |  "settings": {
         |    "index.number_of_shards": $numOfShards
         |  }
         |}""".stripMargin))
    request
  }

  private def createReindexRequest(source: ReindexSource, destIndexName: String): HttpPost = {
    def sourceSection(source: ReindexSource) = {
      source match {
        case ReindexSource.Local(index, _) if Version.greaterOrEqualThan(esVersion, 8, 0, 0) =>
          s"""
             |"index": "$index"
             |""".stripMargin
        case ReindexSource.Local(index, indexType) =>
          s"""
             |"index": "$index",
             |"type": "$indexType"
             |""".stripMargin
        case ReindexSource.Remote(index, address, username, password, _) if Version.greaterOrEqualThan(esVersion, 8, 0, 0) =>
          s"""
             |"index": "$index",
             |"remote": {
             |  "host": "$address",
             |  "username": "$username",
             |  "password": "$password"
             |}
             |""".stripMargin
        case ReindexSource.Remote(index, address, username, password, indexType) =>
          s"""
             |"index": "$index",
             |"type": "$indexType",
             |"remote": {
             |  "host": "$address",
             |  "username": "$username",
             |  "password": "$password"
             |}
             |""".stripMargin
      }
    }

    val request = new HttpPost(client.from("/_reindex"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""
         |{
         |	"source": {
         |		${sourceSection(source)}
         |	},
         |	"dest": {
         |		"index": "$destIndexName"
         |	}
         |}""".stripMargin))
    request
  }

  private def createCloseIndexRequest(indexName: String) = {
    new HttpPost(client.from(s"/$indexName/_close"))
  }

  private def createStatsRequest(indexNames: Iterable[String]) = {
    indexNames.toList match {
      case Nil => new HttpGet(client.from(s"/_stats"))
      case names => new HttpGet(client.from(s"/${names.mkString(",")}/_stats"))
    }
  }

  class GetIndexResponse(response: HttpResponse) extends JsonResponse(response) {
    lazy val indicesAndAliases: Map[String, Set[String]] =
      responseJson.obj.toMap.map { case (indexName, json) =>
        val aliases = json("aliases").obj.keys.toSet
        (indexName, aliases)
      }
  }

  class AliasesResponse(response: HttpResponse) extends JsonResponse(response) {
    lazy val aliasesOfIndices: Map[String, Set[String]] =
      responseJson.obj.toMap.map { case (indexName, json) =>
        val aliases = json("aliases").obj.keys.toSet
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

    lazy val dataStreams: List[DataStreamDescription] =
      responseJson
        .obj.toMap.get("data_streams")
        .map(_.arr.toList).getOrElse(List.empty)
        .map { vJson =>
          DataStreamDescription(
            vJson("name").str,
            vJson.obj.get("backing_indices").map(_.arr.toList).getOrElse(List.empty).map(_.str)
          )
        }

    sealed case class IndexDescription(name: String, aliases: List[String], attributes: List[String])
    sealed case class AliasDescription(name: String, indices: List[String])
    sealed case class DataStreamDescription(name: String, backingIndices: List[String])
  }

  class StatsResponse(response: HttpResponse) extends JsonResponse(response) {

    lazy val indexNames: Set[String] = responseJson.obj("indices").obj.keys.toSet
  }
}
object IndexManager {

  sealed trait ReindexSource {
    def indexName: String

    def `type`: Option[String]
  }
  object ReindexSource {
    final case class Local(indexName: String, `type`: Option[String] = None) extends ReindexSource
    final case class Remote(indexName: String, address: String, username: String, password: String, `type`: Option[String] = None) extends ReindexSource
  }

  sealed trait AliasAction
  object AliasAction {
    final case class Add(index: String, alias: String, filter: Option[JSON] = None) extends AliasAction
    final case class Delete(index: String, alias: String) extends AliasAction
  }
}
