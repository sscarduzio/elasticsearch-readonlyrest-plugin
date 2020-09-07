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
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse}
import tech.beshu.ror.utils.elasticsearch.XpackApiManager.{RollupCapabilitiesResult, RollupJobsResult}
import tech.beshu.ror.utils.httpclient.{HttpGetWithEntity, RestClient}
import tech.beshu.ror.utils.misc.ScalaUtils.waitForCondition
import tech.beshu.ror.utils.misc.Version

class XpackApiManager(client: RestClient,
                      esVersion: String)
  extends BaseManager(client) {

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


  private def createRollupRequest(jobId: String,
                                  indexPattern: String,
                                  rollupIndex: String,
                                  timestampField: String,
                                  aggregableField: String) = {
    val endpoint =
      if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) s"/_rollup/job/$jobId"
      else s"/_xpack/rollup/job/$jobId"
    val request = new HttpPut(client.from(endpoint))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(rollupRequestEntityString(esVersion, indexPattern, rollupIndex, timestampField, aggregableField))
    request
  }

  private def rollupRequestEntityString(esVersion:String,
                                        indexPattern: String,
                                        rollupIndex: String,
                                        timestampField: String,
                                        aggregableField: String) = {
    if (Version.greaterOrEqualThan(esVersion, 7, 2, 0)) {
      new StringEntity(
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
           |  "metrics": [
           |    {
           |      "field": "$aggregableField",
           |      "metrics": [ "min", "max", "sum" ]
           |    }
           |  ]
           |}
      """.stripMargin)
    } else {
      new StringEntity(
        s"""
           |{
           |  "index_pattern": "$indexPattern",
           |  "rollup_index": "$rollupIndex",
           |  "cron": "* * */2 * * ?",
           |  "page_size": 1000,
           |  "groups": {
           |    "date_histogram": {
           |      "field": "$timestampField",
           |      "interval": "1h",
           |      "delay": "7d"
           |    },
           |    "terms": {
           |       "fields": [ "$aggregableField" ]
           |     }
           |  },
           |  "metrics": [
           |    {
           |      "field": "$aggregableField",
           |      "metrics": [ "min", "max", "sum" ]
           |    }
           |  ]
           |}
      """.stripMargin)
    }
  }


  private def createDeleteRollupJobRequest(jobId: String) = {
    val endpoint =
      if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) s"/_rollup/job/$jobId"
      else s"/_xpack/rollup/job/$jobId"
    new HttpDelete(client.from(endpoint))
  }

  private def createGetRollupJobsRequest(jobId: String) = {
    val endpoint =
      if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) s"/_rollup/job/$jobId"
      else s"/_xpack/rollup/job/$jobId"
    new HttpGet(client.from(endpoint))
  }

  private def createGetRollupJobCapabilitiesRequest(index: String) = {
    val endpoint =
      if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) s"/_rollup/data/$index"
      else s"/_xpack/rollup/data/$index"
    new HttpGet(client.from(endpoint))
  }

  private def createGetRollupIndexCapabilitiesRequest(indices: List[String]) = {
    val endpoint =
      if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) s"/${indices.mkString(",")}/_rollup/data"
      else s"/${indices.mkString(",")}/_xpack/rollup/data"
    new HttpGet(client.from(endpoint))
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
}

object XpackApiManager {

  class RollupJobsResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val jobs: List[JSON] = responseJson("jobs").arr.toList
  }

  class RollupCapabilitiesResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val capabilities: Map[String, List[JSON]] = {
      responseJson.obj.toMap.mapValues(_("rollup_jobs").arr.toList)
    }
  }
}