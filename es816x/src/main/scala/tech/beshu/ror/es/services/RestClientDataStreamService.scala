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

import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.elasticsearch.client.{Request, Response, ResponseException, RestClient}
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, TemplateName}
import tech.beshu.ror.es.DataStreamService
import tech.beshu.ror.es.DataStreamService.DataStreamSettings.*
import tech.beshu.ror.es.DataStreamService.{CreationResult, DataStreamSettings}
import tech.beshu.ror.es.services.DataStreamSettingsOps.*
import tech.beshu.ror.es.utils.RestResponseOps.*

final class RestClientDataStreamService(client: RestClient) extends DataStreamService {

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

  override protected def checkIndexLifecyclePolicyExists(policyId: NonEmptyString): Task[Boolean] = execute {
    val policy = policyId.value
    val request = new Request("GET", s"/_ilm/policy/$policy")
    perform(request)
      .flatMap {
        case response if response.isSuccess =>
          val policies = response.entityJson.obj.keySet
          Task.pure(policies.contains(policy))
        case response =>
          failure(s"Cannot get ILM policy [$policy] - response code: ${response.statusCode}")
      }
      .onErrorRecoverWith {
        case ex: ResponseException if ex.getResponse.errorType.contains("resource_not_found_exception") =>
          Task.pure(false)
        case ex: Throwable =>
          Task.raiseError(ex)
      }
  }

  override protected def createIndexLifecyclePolicy(policy: DataStreamSettings.LifecyclePolicy): Task[CreationResult] = execute {
    val policyName = policy.id.value
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

  override protected def checkComponentTemplateExists(templateName: TemplateName): Task[Boolean] = execute {
    val templateId = templateName.value.value
    val request = new Request("GET", s"/_component_template/$templateId")
    perform(request)
      .flatMap {
        case response if response.isSuccess =>
          val componentTemplates = response.entityJson("component_templates").arr.map(_("name").str)
          Task.pure(componentTemplates.contains(templateId))
        case response =>
          failure(s"Cannot get component template [$templateId] - response code: ${response.statusCode}")
      }
      .onErrorRecoverWith {
        case ex: ResponseException if ex.getResponse.errorType.contains("resource_not_found_exception") =>
          Task.pure(false)
        case ex: Throwable =>
          Task.raiseError(ex)
      }
  }

  override protected def createComponentTemplateForMappings(settings: ComponentTemplateMappings): Task[CreationResult] = execute {
    val templateId = settings.templateName.value.value
    val request = new Request("PUT", s"/_component_template/$templateId")
    val requestBody = ujson.Obj(
      "template" -> ujson.Obj("mappings" -> settings.mappingsJson),
      "_meta" -> settings.metadata
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

  override protected def createComponentTemplateForIndex(settings: ComponentTemplateSettings): Task[CreationResult] = execute {
    val templateId = settings.templateName.value.value
    val request = new Request("PUT", s"/_component_template/$templateId")
    val requestBody = ujson.Obj(
      "template" -> ujson.Obj("settings" -> ujson.Obj("index.lifecycle.name" -> settings.lifecyclePolicyId.value)),
      "_meta" -> settings.metadata
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

  override protected def checkIndexTemplateExists(templateName: TemplateName): Task[Boolean] = {
    val templateId = templateName.value.value
    val request = new Request("GET", s"/_index_template/$templateId")
    perform(request)
      .flatMap {
        case response if response.isSuccess =>
          val indexTemplates = response.entityJson("index_templates").arr.map(_("name").str)
          Task.pure(indexTemplates.contains(templateId))
        case response =>
          failure(s"Cannot get index template [$templateId] - response code: ${response.statusCode}")
      }
      .onErrorRecoverWith {
        case ex: ResponseException if ex.getResponse.errorType.contains("resource_not_found_exception") =>
          Task.pure(false)
        case ex: Throwable =>
          Task.raiseError(ex)
      }
  }

  override protected def createIndexTemplate(settings: IndexTemplateSettings): Task[CreationResult] = execute {
    val templateId = settings.templateName.value.value
    val request = new Request("PUT", s"/_index_template/$templateId")
    val requestBody = ujson.Obj(
      "index_patterns" -> List(settings.dataStreamName.value.value),
      "data_stream" -> Map.empty,
      "composed_of" -> settings.componentTemplates.toList.map(_.value.value),
      "priority" -> 500,
      "_meta" -> settings.metadata
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

  override protected def createDataStream(dataStreamName: DataStreamName.Full): Task[CreationResult] = execute {
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
