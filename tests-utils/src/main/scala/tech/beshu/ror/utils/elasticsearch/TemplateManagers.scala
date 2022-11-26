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
import tech.beshu.ror.utils.elasticsearch.BaseTemplateManager._
import tech.beshu.ror.utils.elasticsearch.ComponentTemplateManager.ComponentTemplatesResponse
import tech.beshu.ror.utils.elasticsearch.IndexTemplateManager.SimulateResponse
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils.waitForCondition
import tech.beshu.ror.utils.misc.Version

abstract class BaseTemplateManager(client: RestClient,
                                   parseTemplates: JSON => List[Template])
  extends BaseManager(client) {

  def getTemplate(name: String): TemplateResponse =
    call(createGetTemplateRequest(name), new TemplateResponse(_, parseTemplates))

  def getTemplates: TemplatesResponse =
    call(createGetTemplatesRequest, new TemplatesResponse(_, parseTemplates))

  def putTemplate(templateName: String,
                  indexPatterns: NonEmptyList[String],
                  aliases: Set[String] = Set.empty,
                  priority: Int = 0): SimpleResponse =
    call(createIndexTemplateRequest(templateName, putTemplateBodyJson(indexPatterns, aliases, priority)), new SimpleResponse(_))

  def putTemplateAndWaitForIndexing(templateName: String,
                                    indexPatterns: NonEmptyList[String],
                                    aliases: Set[String] = Set.empty,
                                    priority: Int = 0): Unit = {
    putTemplate(templateName, indexPatterns, aliases, priority).force()
    waitForCondition(s"Putting index template $templateName") {
      getTemplate(templateName).responseCode == 200
    }
  }

  def deleteTemplate(name: String): SimpleResponse =
    call(createDeleteTemplateRequest(name), new SimpleResponse(_))

  def deleteAllTemplates(): SimpleResponse =
    call(createDeleteAllTemplatesRequest(), new SimpleResponse(_))

  protected def createGetTemplateRequest(name: String): HttpGet

  protected def createGetTemplatesRequest: HttpGet

  protected def createDeleteTemplateRequest(templateName: String): HttpDelete

  protected def createDeleteAllTemplatesRequest(): HttpDelete

  protected def createIndexTemplateRequest(templateName: String,
                                           body: JSON): HttpPut

  protected def putTemplateBodyJson(indexPatterns: NonEmptyList[String],
                                    aliases: Set[String],
                                    priority: Int): JSON
}

object BaseTemplateManager {
  class TemplateResponse(response: HttpResponse,
                         parseTemplates: JSON => List[Template])
    extends TemplatesResponse(response, parseTemplates) {

    lazy val template: Template = templates.head
  }

  class TemplatesResponse(response: HttpResponse,
                          parseTemplates: JSON => List[Template])
    extends JsonResponse(response) {

    lazy val templates: List[Template] = parseTemplates(responseJson)
  }

  final case class Template(name: String, patterns: Set[String], aliases: Set[String])
}

class LegacyTemplateManager(client: RestClient, esVersion: String)
  extends BaseTemplateManager(
    client,
    if(Version.greaterOrEqualThan(esVersion, 6, 0, 0)) LegacyTemplateManager.parseTemplates
    else LegacyTemplateManager.parseTemplatesEs5x
  ) {

  override protected def createGetTemplateRequest(name: String): HttpGet = {
    val request = new HttpGet(client.from("/_template/" + name))
    request.setHeader("timeout", "50s")
    request
  }

  override protected def createGetTemplatesRequest: HttpGet = {
    val request = new HttpGet(client.from("/_template"))
    request.setHeader("timeout", "50s")
    request
  }

  override protected def createDeleteTemplateRequest(templateName: String): HttpDelete = {
    val request = new HttpDelete(client.from(s"/_template/$templateName"))
    request.setHeader("timeout", "50s")
    request
  }

  override protected def createDeleteAllTemplatesRequest(): HttpDelete = {
    val request = new HttpDelete(client.from("/_template/*"))
    request.setHeader("timeout", "50s")
    request
  }

  override protected def createIndexTemplateRequest(templateName: String,
                                                    body: JSON): HttpPut = {
    val request = new HttpPut(client.from(s"/_template/$templateName"))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(body)))
    request
  }

  override protected def putTemplateBodyJson(indexPatterns: NonEmptyList[String], aliases: Set[String], priority: Int): JSON = {
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
           |}""".stripMargin
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
}

object LegacyTemplateManager {
  private def parseTemplates(content: JSON) = {
    content
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

  private def parseTemplatesEs5x(content: JSON) = {
    content
      .obj
      .map { case (name, templateContent) =>
        Template(
          name,
          Set(templateContent.obj("template").str),
          templateContent.obj("aliases").obj.keys.toSet
        )
      }
      .toList
  }
}

class IndexTemplateManager(client: RestClient, esVersion: String)
  extends BaseTemplateManager(client, IndexTemplateManager.parseTemplates) {

  require(
    Version.greaterOrEqualThan(esVersion, 7, 8, 0),
    "New template API is supported starting from ES 7.8.0"
  )

  def simulateIndex(indexName: String): SimulateResponse = {
    call(createSimulateIndexRequest(indexName), new SimulateResponse(_))
  }

  def simulateTemplate(templateName: String): SimulateResponse = {
    call(createSimulateTemplateRequest(templateName), new SimulateResponse(_))
  }

  def simulateNewTemplate(indexPatterns: NonEmptyList[String], aliases: Set[String]): SimulateResponse = {
    call(createSimulateTemplateRequest(indexPatterns, aliases), new SimulateResponse(_))
  }

  def createTemplate(templateName: String,
                     body: JSON): JsonResponse = {
    call(createIndexTemplateRequest(templateName, body), new JsonResponse(_))
  }

  override protected def createGetTemplateRequest(name: String): HttpGet = {
    val request = new HttpGet(client.from("/_index_template/" + name))
    request.setHeader("timeout", "50s")
    request
  }

  override protected def createGetTemplatesRequest: HttpGet = {
    val request = new HttpGet(client.from("/_index_template"))
    request.setHeader("timeout", "50s")
    request
  }

  override protected def createIndexTemplateRequest(templateName: String,
                                                    body: JSON): HttpPut = {
    val request = new HttpPut(client.from(s"/_index_template/$templateName"))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(body)))
    request
  }

  override protected def createDeleteTemplateRequest(templateName: String): HttpDelete = {
    val request = new HttpDelete(client.from(s"/_index_template/$templateName"))
    request.setHeader("timeout", "50s")
    request
  }

  override protected def createDeleteAllTemplatesRequest(): HttpDelete = {
    val request = new HttpDelete(client.from("/_index_template/*"))
    request.setHeader("timeout", "50s")
    request
  }

  override protected def putTemplateBodyJson(indexPatterns: NonEmptyList[String],
                                             aliases: Set[String],
                                             priority: Int): JSON = {
    val allIndexPattern = indexPatterns.toList
    val patternsString = allIndexPattern.mkString("\"", "\",\"", "\"")
    ujson.read {
      s"""
         |{
         |  "index_patterns":[$patternsString],
         |  "priority": $priority,
         |  "template": {
         |    "aliases":{
         |      ${aliases.toList.map(a => s""""$a":{}""").mkString(",\n")}
         |    },
         |    "settings":{"number_of_shards":1},
         |    "mappings":{"properties":{"created_at":{"type":"date","format":"EEE MMM dd HH:mm:ss Z yyyy"}}}
         |  }
         |}""".stripMargin
    }
  }

  private def createSimulateIndexRequest(indexName: String) = {
    val request = new HttpPost(client.from(s"/_index_template/_simulate_index/$indexName"))
    request.setHeader("timeout", "50s")
    request
  }

  private def createSimulateTemplateRequest(templateName: String) = {
    val request = new HttpPost(client.from(s"/_index_template/_simulate/$templateName"))
    request.setHeader("timeout", "50s")
    request
  }

  private def createSimulateTemplateRequest(indexPatterns: NonEmptyList[String], aliases: Set[String]) = {
    val request = new HttpPost(client.from(s"/_index_template/_simulate/"))
    request.setEntity(new StringEntity(ujson.write(putTemplateBodyJson(indexPatterns, aliases, 1))))
    request.setHeader("Content-Type", "application/json")
    request.setHeader("timeout", "50s")
    request
  }
}

object IndexTemplateManager {
  private def parseTemplates(content: JSON) = {
    content("index_templates")
      .arr
      .map { templateJson =>
        Template(
          templateJson("name").str,
          templateJson.obj("index_template")("index_patterns").arr.map(_.str).toSet,
          (for {
            template <- templateJson.obj("index_template").obj.get("template")
            aliases <- template.obj.get("aliases")
          } yield aliases.obj.keys.toSet).getOrElse(Set.empty)
        )
      }
      .toList
  }

  class SimulateResponse(response: HttpResponse)
    extends JsonResponse(response) {

    lazy val templateAliases: Set[String] = (for {
      template <- responseJson.obj.get("template")
      aliasesMap <- template.obj.get("aliases")
      aliases = aliasesMap.obj.keys.toSet
    } yield aliases).getOrElse(Set.empty)

    lazy val overlappingTemplates: List[Template] =
      responseJson
        .obj.get("overlapping").map(_.arr.toList).getOrElse(List.empty)
        .map(t => Template(t("name").str, t("index_patterns").arr.map(_.str).toSet, Set.empty))
  }
}

class ComponentTemplateManager(client: RestClient, esVersion: String)
  extends BaseManager(client) {

  require(
    Version.greaterOrEqualThan(esVersion, 7, 8, 0),
    "New template API is supported starting from ES 7.8.0"
  )

  def getTemplates: ComponentTemplatesResponse = {
    call(createGetComponentTemplatesRequest, new ComponentTemplatesResponse(_))
  }

  def getTemplate(templateName: String): ComponentTemplatesResponse = {
    call(createGetComponentTemplatesRequest(templateName), new ComponentTemplatesResponse(_))
  }

  def putTemplate(templateName: String, aliases: Set[String]): SimpleResponse = {
    call(createPutComponentTemplateRequest(templateName, aliases), new SimpleResponse(_))
  }

  def putTemplateAndWaitForIndexing(templateName: String,
                                    aliases: Set[String] = Set.empty): Unit = {
    putTemplate(templateName, aliases).force()
    waitForCondition(s"Putting index template $templateName") {
      getTemplate(templateName).responseCode == 200
    }
  }


  def deleteTemplate(templateName: String): SimpleResponse = {
    call(createDeleteComponentTemplateRequest(templateName), new SimpleResponse(_))
  }

  private def createGetComponentTemplatesRequest = {
    new HttpGet(client.from(s"/_component_template"))
  }

  private def createGetComponentTemplatesRequest(templateName: String) = {
    new HttpGet(client.from(s"/_component_template/$templateName"))
  }

  private def createPutComponentTemplateRequest(templateName: String, aliases: Set[String]) = {
    val request = new HttpPut(client.from(s"/_component_template/$templateName"))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(putComponentTemplateBodyJson(aliases))))
    request
  }

  private def createDeleteComponentTemplateRequest(templateName: String) = {
    new HttpDelete(client.from(s"/_component_template/$templateName"))
  }

  private def putComponentTemplateBodyJson(aliases: Set[String]) = ujson.read {
    s"""
       |{
       |  "template": {
       |    "settings" : {
       |      "number_of_shards" : 1
       |    },
       |    "aliases" : {
       |      ${aliases.toList.map(a => s""""$a":{}""").mkString(",\n")}
       |    }
       |  }
       |}""".stripMargin
  }
}

object ComponentTemplateManager {

  class ComponentTemplatesResponse(response: HttpResponse)
    extends JsonResponse(response) {

    lazy val templates: List[ComponentTemplate] = {
      responseJson("component_templates")
        .arr
        .map { templateJson =>
          ComponentTemplate(
            templateJson("name").str,
            (for {
              template <- templateJson.obj("component_template").obj.get("template")
              aliases <- template.obj.get("aliases")
            } yield aliases.obj.keys.toSet).getOrElse(Set.empty)
          )
        }
        .toList
    }
  }

  final case class ComponentTemplate(name: String, aliases: Set[String])
}