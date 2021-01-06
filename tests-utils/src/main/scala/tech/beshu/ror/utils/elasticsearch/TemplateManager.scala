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

import java.time.Duration
import java.util.function.BiPredicate

import cats.data.NonEmptyList
import net.jodah.failsafe.{Failsafe, RetryPolicy}
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils._

class TemplateManager(client: RestClient)
  extends BaseManager(client) {

  // old API
  def getTemplate(name: String): JsonResponse =
    call(createGetTemplateRequest(name), new JsonResponse(_))

  def getTemplates: JsonResponse =
    call(createGetTemplatesRequest, new JsonResponse(_))

  def insertTemplate(name: String, templateContent: JSON): JsonResponse =
    call(createInsertTemplateRequest(name, templateContent), new JsonResponse(_))

  def insertTemplateAndWaitForIndexing(name: String, templateContent: JSON): Unit = {
    val result = insertTemplate(name, templateContent)
    if (!result.isSuccess) throw new IllegalStateException("Cannot insert template: [" + result.responseCode + "]\nResponse: " + result.body)
    val retryPolicy = new RetryPolicy[Boolean]()
      .handleIf(isNotIndexedYet)
      .withMaxRetries(20)
      .withDelay(Duration.ofMillis(200))
    Failsafe
      .`with`[Boolean, RetryPolicy[Boolean]](retryPolicy)
      .get(() => isTemplateIndexed(name))
  }

  def deleteTemplate(name: String): JsonResponse =
    call(createDeleteTemplateRequest(name), new JsonResponse(_))

  def deleteAllTemplates(): JsonResponse =
    call(createDeleteAllTemplatesRequest(), new JsonResponse(_))

  // new API
  def putIndexTemplate(templateName: String,
                       indexPatterns: NonEmptyList[String],
                       template: JSON): JsonResponse = {
    call(createPutIndexTemplateRequest(templateName, indexPatterns, template), new JsonResponse(_))
  }

  private def createGetTemplateRequest(name: String) = {
    val request = new HttpGet(client.from("/_template/" + name))
    request.setHeader("timeout", "50s")
    request
  }

  private def createGetTemplatesRequest = {
    val request = new HttpGet(client.from("/_template"))
    request.setHeader("timeout", "50s")
    request
  }

  private def createDeleteTemplateRequest(templateName: String) = {
    val request = new HttpDelete(client.from(s"/_template/$templateName"))
    request.setHeader("timeout", "50s")
    request
  }

  private def createDeleteAllTemplatesRequest() = {
    val request = new HttpDelete(client.from("/_template/*"))
    request.setHeader("timeout", "50s")
    request
  }

  private def createInsertTemplateRequest(templateName: String, templateContent: JSON) = try {
    val request = new HttpPut(client.from(s"/_template/$templateName"))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(templateContent)))
    request
  } catch {
    case ex: Exception =>
      throw new IllegalStateException("Cannot insert document", ex)
  }

  private def createPutIndexTemplateRequest(templateName: String, indexPatterns: NonEmptyList[String], template: JSON) = {
    val request = new HttpPut(client.from(s"/_index_template/$templateName" ))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""
         |{
         |  "index_patterns": ${indexPatterns.toList.mkJsonStringArray},
         |  "template": ${ujson.write(template)}
         |}
       """.stripMargin))
    request
  }


  private def isTemplateIndexed(templateName: String) =
    getTemplates.responseJson.obj.contains(templateName)

  private def isNotIndexedYet: BiPredicate[Boolean, Throwable] =
    (indexed: Boolean, throwable: Throwable) => throwable != null || !indexed
}
