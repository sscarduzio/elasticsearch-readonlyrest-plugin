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
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils.waitForCondition

class IndexLifecycleManager(client: RestClient, esVersion: String)
  extends BaseManager(client, esVersion, esNativeApi = true) {

  def getPolicy(id: String): PoliciesResponse = {
    call(createGetPolicyRequest(id), new PoliciesResponse(_))
  }

  def putPolicy(id: String, policy: JSON): SimpleResponse = {
    call(createPutPolicyRequest(id, policy), new SimpleResponse(_))
  }

  def putPolicyAndWaitForIndexing(id: String, policy: JSON): Unit = {
    putPolicy(id, policy).force()
    waitForCondition(s"Putting index lifecycle policy $id") {
      getPolicy(id).responseCode == 200
    }
  }

  def deletePolicy(id: String): SimpleResponse = {
    call(createDeletePolicyRequest(id), new SimpleResponse(_))
  }

  def startIlm(): SimpleResponse = {
    call(createStartIlmRequest(), new SimpleResponse(_))
  }

  def stopIlm(): SimpleResponse = {
    call(createStopIlmRequest(), new SimpleResponse(_))
  }

  def ilmStatus(): IlmStatusResponse = {
    call(createIlmStatusRequest(), new IlmStatusResponse(_))
  }

  def ilmExplain(index: String, indices: String*): IlmExplainResponse = {
    call(createIlmExplainRequest(NonEmptyList.of(index, indices: _*)), new IlmExplainResponse(_))
  }

  def moveToLifecycleStep(index: String, currentStep: JSON, nextStep: JSON): SimpleResponse = {
    call(createMoveToLifecycleStepRequest(index, currentStep, nextStep), new SimpleResponse(_))
  }

  def retryPolicyExecution(index: String, indices: String*): SimpleResponse = {
    call(createRetryPolicyExecutionRequest(NonEmptyList.of(index, indices: _*)), new SimpleResponse(_))
  }

  def removePolicyFromIndex(index: String, indices: String*): SimpleResponse = {
    call(createRemovePolicyFromIndexRequest(NonEmptyList.of(index, indices: _*)), new SimpleResponse(_))
  }

  private def createPutPolicyRequest(id: String, policy: JSON) = {
    val request = new HttpPut(client.from(s"_ilm/policy/$id"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(policy)))
    request
  }

  private def createDeletePolicyRequest(id: String) = {
    new HttpDelete(client.from(s"_ilm/policy/$id"))
  }

  private def createGetPolicyRequest(id: String) = {
    new HttpGet(client.from(s"_ilm/policy/$id"))
  }

  private def createStartIlmRequest() = {
    new HttpPost(client.from(s"_ilm/start"))
  }

  private def createStopIlmRequest() = {
    new HttpPost(client.from(s"_ilm/stop"))
  }

  private def createIlmStatusRequest() = {
    new HttpGet(client.from(s"_ilm/status"))
  }

  private def createIlmExplainRequest(indices: NonEmptyList[String]) = {
    new HttpGet(client.from(s"${indices.toList.mkString(",")}/_ilm/explain"))
  }

  private def createMoveToLifecycleStepRequest(index: String, currentStep: JSON, nextStep: JSON) = {
    val request = new HttpPost(client.from(s"_ilm/move/$index"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""{
         |  "current_step": ${ujson.write(currentStep)},
         |  "next_step": ${ujson.write(nextStep)}
         |}""".stripMargin))
    request
  }

  private def createRetryPolicyExecutionRequest(indices: NonEmptyList[String]) = {
    new HttpPost(client.from(s"${indices.toList.mkString(",")}/_ilm/retry"))
  }

  private def createRemovePolicyFromIndexRequest(indices: NonEmptyList[String]) = {
    new HttpPost(client.from(s"${indices.toList.mkString(",")}/_ilm/remove"))
  }

  class PoliciesResponse(response: HttpResponse) extends JsonResponse(response) {
    lazy val policies: Map[String, JSON] =
      responseJson.obj.toMap.map { case (policyName, json) =>
        val policy = ujson.Obj.from(json.obj.view.filterKeys(_ == "policy").toList)
        (policyName, policy)
      }
  }

  class IlmStatusResponse(response: HttpResponse) extends JsonResponse(response) {
    lazy val mode: String = responseJson.obj("operation_mode").str
  }

  class IlmExplainResponse(response: HttpResponse) extends JsonResponse(response) {
    lazy val indices: Map[String, JSON] = responseJson.obj("indices").obj.toMap
  }
}