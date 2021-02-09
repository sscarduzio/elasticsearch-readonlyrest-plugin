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
import org.apache.http.HttpResponse
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse, SimpleResponse}
import tech.beshu.ror.utils.elasticsearch.LegacyTemplateManager.{TemplateResponse, TemplatesResponse}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils._
import tech.beshu.ror.utils.misc.Version

trait TemplateManager {
  def getTemplate(name: String): TemplateResponse

  def getTemplates: TemplatesResponse

  def insertTemplate(name: String, templateContent: JSON): SimpleResponse

  def insertTemplateAndWaitForIndexing(name: String, templateContent: JSON): Unit

  def deleteTemplate(name: String): SimpleResponse
}

class LegacyTemplateManager(client: RestClient, esVersion: String)
  extends BaseManager(client) {

  // old API
  def getTemplate(name: String): TemplateResponse =
    call(createGetTemplateRequest(name), new TemplateResponse(_))

  def getTemplates: TemplatesResponse =
    call(createGetTemplatesRequest, new TemplatesResponse(_))

  def insertTemplate(templateName: String,
                     indexPatterns: NonEmptyList[String],
                     aliases: Set[String] = Set.empty): SimpleResponse =
    call(createInsertLegacyTemplateRequest(templateName, indexPatterns, aliases), new SimpleResponse(_))

  def insertTemplateAndWaitForIndexing(templateName: String,
                                       indexPatterns: NonEmptyList[String],
                                       aliases: Set[String] = Set.empty): Unit = {
    val result = insertTemplate(templateName, indexPatterns, aliases)
    if (!result.isSuccess) throw new IllegalStateException("Cannot insert template: [" + result.responseCode + "]\nResponse: " + result.body)
    val retryPolicy = new RetryPolicy[Boolean]()
      .handleIf(isNotIndexedYet)
      .withMaxRetries(20)
      .withDelay(Duration.ofMillis(200))
    Failsafe
      .`with`[Boolean, RetryPolicy[Boolean]](retryPolicy)
      .get(() => isTemplateIndexed(templateName))
  }

  def deleteTemplate(name: String): SimpleResponse =
    call(createDeleteTemplateRequest(name), new SimpleResponse(_))

  def deleteAllTemplates(): SimpleResponse =
    call(createDeleteAllTemplatesRequest(), new SimpleResponse(_))

  // new API
  def putIndexTemplate(templateName: String,
                       indexPatterns: NonEmptyList[String],
                       template: JSON): SimpleResponse = {
    call(createPutIndexTemplateRequest(templateName, indexPatterns, template), new SimpleResponse(_))
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

  private def createInsertLegacyTemplateRequest(templateName: String,
                                                indexPatterns: NonEmptyList[String],
                                                aliases: Set[String]) = {
    val request = new HttpPut(client.from(s"/_template/$templateName"))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(putTemplateBodyJson(indexPatterns, aliases))))
    request
  }

  private def createPutIndexTemplateRequest(templateName: String,
                                            indexPatterns: NonEmptyList[String],
                                            template: JSON) = {
    val request = new HttpPut(client.from(s"/_index_template/$templateName"))
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

  private def putTemplateBodyJson(indexPatterns: NonEmptyList[String], aliases: Set[String]): JSON = {
    val allIndexPattern = indexPatterns.toList
    val patternsString = allIndexPattern.mkString("\"", "\",\"", "\"")
    if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
      ujson.read {
        s"""
           |{
           |  "index_patterns":[$patternsString],
           |  "aliases":{
           |    ${aliases.toList.map(a => s""""$a":{}""").mkString(",\n")}
           |  },
           |  "settings":{"number_of_shards":1},
           |  "mappings":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}
           |}""".stripMargin
      }
    } else if (Version.greaterOrEqualThan(esVersion, 6, 0, 0)) {
      ujson.read {
        s"""
           |{
           |  "index_patterns":[$patternsString],
           |  "aliases":{
           |    ${aliases.toList.map(a => s""""$a":{}""").mkString(",\n")}
           |  },
           |  "settings":{"number_of_shards":1},
           |  "mappings":{"doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}
           ||}""".stripMargin
      }
    } else {
      if (allIndexPattern.size == 1) {
        ujson.read {
          s"""
             |{
             |  "template":"${allIndexPattern.head}",
             |  "aliases":{
             |    ${aliases.toList.map(a => s""""$a":{}""").mkString(",\n")}
             |  },
             |  "settings":{"number_of_shards":1},
             |  "mappings":{"doc":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}}
             |}""".stripMargin
        }
      } else {
        throw new IllegalArgumentException("Cannot create template with more than one index pattern for the ES version < 6.0.0")
      }
    }
  }

  private def isTemplateIndexed(templateName: String) =
    getTemplates.responseJson.obj.contains(templateName)

  private def isNotIndexedYet: BiPredicate[Boolean, Throwable] =
    (indexed: Boolean, throwable: Throwable) => throwable != null || !indexed
}

object LegacyTemplateManager {

  class TemplateResponse(response: HttpResponse) extends TemplatesResponse(response) {
    lazy val template: Template = templates.head
  }

  class TemplatesResponse(response: HttpResponse) extends JsonResponse(response) {
    lazy val templates: List[Template] =
      responseJson
        .obj
        .map { case (name, templateContent) =>
          Template(
            name,
            templateContent.obj("index_patterns").arr.map(_.str).toSet,
            templateContent.obj("aliases").obj.keys.toSet
          )
        }
        .toList

  }

  final case class Template(name: String, patterns: Set[String], aliases: Set[String])

}
