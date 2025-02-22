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
package tech.beshu.ror.es.services

import cats.data.NonEmptyList
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.client.{Request, Response, ResponseException, RestClient}
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, TemplateName}
import tech.beshu.ror.es.DataStreamService
import tech.beshu.ror.es.DataStreamService.{CreationResult, DataStreamSettings}
import tech.beshu.ror.es.services.DataStreamSettingsOps.toJson
import tech.beshu.ror.es.utils.RestResponseOps.*
import ujson.Value

final class RestClientDataStreamService(client: RestClient) extends DataStreamService with Logging {

  override def checkDataStreamExists(dataStreamName: DataStreamName.Full): Task[Boolean] = execute {
    val name = dataStreamName.value.value
    val request = new Request("GET", s"/_data_stream/$name")
    perform(request)
      .flatMap {
        case response if response.isSuccess =>
          val dataStreams = response.entityJson("data_streams").arr.map(_("name").str)
          Task.pure(dataStreams.contains(name))
        case response =>
          failure(s"Cannot get data stream [$name] - response code: ${response.statusCode}")
      }
      .onErrorRecoverWith {
        case ex: ResponseException if ex.getResponse.errorType.contains("index_not_found_exception") =>
          Task.pure(false)
        case ex: Throwable =>
          Task.raiseError(ex)
      }
  }

  override def createIndexLifecyclePolicy(policyName: String, policy: DataStreamSettings.LifecyclePolicy): Task[CreationResult] = execute {
    val request = new Request("PUT", s"/_ilm/policy/$policyName")
    val requestBody = ujson.Obj("policy" -> policy.toJson)
    request.setJsonBody(requestBody)
    perform(request)
      .flatMap {
        case response if response.isSuccess =>
          resultFrom(response)
        case response =>
          failure(s"Cannot create ILM policy [$policyName] - unexpected response code: ${response.statusCode}")
      }
  }

  override def createComponentTemplateForMappings(templateName: TemplateName,
                                                  mappingsJson: Value,
                                                  metadata: Map[String, String]): Task[CreationResult] = execute {
    val templateId = templateName.value.value
    val request = new Request("PUT", s"/_component_template/$templateId")
    val requestBody = ujson.Obj(
      "template" -> ujson.Obj("mappings" -> mappingsJson),
      "_meta" -> metadata
    )
    request.setJsonBody(requestBody)
    perform(request)
      .flatMap {
        case response if response.isSuccess =>
          resultFrom(response)
        case response =>
          failure(s"Cannot create component template [$templateId] - response code: ${response.statusCode}")
      }
  }

  override def createComponentTemplateForIndex(templateName: TemplateName,
                                               lifecyclePolicyName: String,
                                               metadata: Map[String, String]): Task[CreationResult] = execute {
    val templateId = templateName.value.value
    val request = new Request("PUT", s"/_component_template/$templateId")
    val requestBody = ujson.Obj(
      "template" -> ujson.Obj("settings" -> ujson.Obj("index.lifecycle.name" -> lifecyclePolicyName)),
      "_meta" -> metadata
    )
    request.setJsonBody(requestBody)
    perform(request)
      .flatMap {
        case response if response.isSuccess =>
          resultFrom(response)
        case response =>
          failure(s"Cannot create component template [$templateId] - response code: ${response.statusCode}")
      }
  }

  override def createIndexTemplate(templateName: TemplateName,
                                   dataStreamName: DataStreamName.Full,
                                   componentTemplates: NonEmptyList[TemplateName],
                                   metadata: Map[String, String]): Task[CreationResult] = execute {
    val templateId = templateName.value.value
    val request = new Request("PUT", s"/_index_template/$templateId")
    val requestBody = ujson.Obj(
      "index_patterns" -> List(dataStreamName.value.value),
      "data_stream" -> Map.empty,
      "composed_of" -> componentTemplates.toList.map(_.value.value),
      "priority" -> 500,
      "_meta" -> metadata
    )
    request.setJsonBody(requestBody)
    perform(request)
      .flatMap {
        case response if response.isSuccess =>
          resultFrom(response)
        case response =>
          failure(s"Cannot create index template [$templateId] - response code: ${response.statusCode}")
      }
  }

  override def createDataStream(dataStreamName: DataStreamName.Full): Task[CreationResult] = execute {
    val name = dataStreamName.value.value
    val request = new Request("PUT", s"/_data_stream/$name")
    perform(request)
      .flatMap {
        case response if response.isSuccess =>
          resultFrom(response)
        case response =>
          failure(s"Cannot create data stream [$name] - response code: ${response.statusCode}")
      }
  }

  private def execute[A](value: => Task[A]) = Task(value).flatten

  private def perform(request: Request) = {
    Task(client.performRequest(request))
  }

  private def resultFrom(response: Response) = {
    Task.pure(CreationResult(response.entityJson("acknowledged").bool))
  }

  private def failure(message: String) = {
    Task.raiseError(new IllegalStateException(message))
  }
}
