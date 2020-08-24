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
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse, SimpleResponse}
import tech.beshu.ror.utils.elasticsearch.IndexManager.{AliasAction, AliasesResponse, RollupCapabilitiesResult, RollupJobsResult}
import tech.beshu.ror.utils.httpclient.{HttpGetWithEntity, RestClient}
import tech.beshu.ror.utils.misc.ScalaUtils.waitForCondition

class IndexManager(client: RestClient,
                   override val additionalHeaders: Map[String, String] = Map.empty)
  extends BaseManager(client) {

  def getIndex(indices: String*): JsonResponse = {
    call(getIndexRequest(indices.toSet), new JsonResponse(_))
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

  def removeAll: SimpleResponse =
    call(createDeleteIndicesRequest, new SimpleResponse(_))

  def removeAllAliases: SimpleResponse =
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

  def rollup(jobId: String,
             indexPattern: String,
             rollupIndex: String,
             timestampField: String = "timestamp",
             aggregableField: String = "count"): JsonResponse = {
    val response = call(createRollupRequest(jobId, indexPattern, rollupIndex, timestampField, aggregableField), new JsonResponse(_))
    if(response.isSuccess) {
      waitForCondition(s"Job $jobId is indexed") {
        getAllRollupJobs.jobs.exists(_ ("config")("id").str == jobId)
      }
    }
    response
  }

  def deleteRollupJob(jobId: String): JsonResponse = {
    call(createDeleteRollupJobRequest(jobId), new JsonResponse(_))
  }

  def deleteAllRollupJobs(): Unit = {
    getAllRollupJobs.jobs match {
      case Nil =>
      case jobs =>
        jobs.foreach { jobJson =>
          val jobId = jobJson("config")("id").str
          deleteRollupJob(jobId).force()
        }
        waitForCondition("All rollup jobs are deleted") {
          getAllRollupJobs.jobs.isEmpty
        }
    }
  }

  def getAllRollupJobs: RollupJobsResult = getRollupJobs("_all")

  def getRollupJobs(jobId: String): RollupJobsResult = {
    call(createGetRollupJobsRequest(jobId), new RollupJobsResult(_))
  }

  def getRollupJobCapabilities(indexPattern: String): RollupCapabilitiesResult = {
    call(createGetRollupJobCapabilitiesRequest(indexPattern), new RollupCapabilitiesResult(_))
  }

  def getRollupIndexCapabilities(rollupIndex: String, rollupIndices: String*): RollupCapabilitiesResult = {
    call(createGetRollupIndexCapabilitiesRequest(rollupIndex :: rollupIndices.toList), new RollupCapabilitiesResult(_))
  }

  def rollupSearch(rollupIndex: String): JsonResponse = {
    call(createRollupSearchRequest(rollupIndex), new JsonResponse(_))
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

  private def getIndexRequest(indices: Set[String]) = {
    new HttpGet(client.from(indices.mkString(",")))
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

  private def createDeleteIndicesRequest = {
    new HttpDelete(client.from("/_all"))
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

  private def createRollupRequest(jobId: String,
                                  indexPattern: String,
                                  rollupIndex: String,
                                  timestampField: String,
                                  aggregableField: String) = {
    val request = new HttpPut(client.from(s"/_rollup/job/$jobId"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""
        |{
        |  "index_pattern": "$indexPattern",
        |  "rollup_index": "$rollupIndex",
        |  "cron": "* * */2 * * ?",
        |  "page_size": 1000,
        |  "groups": {
        |    "date_histogram": {
        |      "field": "$timestampField",
        |      "fixed_interval": "1h",
        |      "delay": "7d"
        |    },
        |    "terms": {
        |       "fields": [ "$aggregableField" ]
        |     }
        |  },
        |  "metrics": {
        |    "field": "$aggregableField",
        |    "metrics": [ "min", "max", "sum" ]
        |  }
        |}
      """.stripMargin))
    request
  }

  private def createDeleteRollupJobRequest(jobId: String) = {
    new HttpDelete(client.from(s"/_rollup/job/$jobId"))
  }

  private def createGetRollupJobsRequest(jobId: String) = {
    new HttpGet(client.from(s"/_rollup/job/$jobId"))
  }

  private def createGetRollupJobCapabilitiesRequest(index: String) = {
    new HttpGet(client.from(s"/_rollup/data/$index"))
  }

  private def createGetRollupIndexCapabilitiesRequest(indices: List[String]) = {
    new HttpGet(client.from(s"/${indices.mkString(",")}/_rollup/data"))
  }

  private def createRollupSearchRequest(rollupIndex: String) = {
    val request = new HttpGetWithEntity(client.from(s"/$rollupIndex/_rollup_search"))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""
         |{
         |  "size": 0,
         |  "aggregations": {
         |    "max_count": {
         |      "max": {
         |        "field": "count"
         |      }
         |    }
         |  }
         |}
       """.stripMargin))
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

  class RollupJobsResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val jobs: List[JSON] = responseJson("jobs").arr.toList
  }

  class RollupCapabilitiesResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val capabilities: Map[String, List[JSON]] = {
      responseJson.obj.toMap.mapValues(_("rollup_jobs").arr.toList)
    }
  }
}