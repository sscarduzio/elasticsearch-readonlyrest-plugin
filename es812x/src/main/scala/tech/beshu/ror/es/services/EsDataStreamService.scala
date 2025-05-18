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
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.ResourceNotFoundException
import org.elasticsearch.action.admin.indices.template.get.{GetComponentTemplateAction, GetComposableIndexTemplateAction}
import org.elasticsearch.action.admin.indices.template.put.{PutComponentTemplateAction, PutComposableIndexTemplateAction}
import org.elasticsearch.action.datastreams.{CreateDataStreamAction, GetDataStreamAction}
import org.elasticsearch.action.support.TransportAction
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.action.{ActionRequest, ActionResponse, ActionType}
import org.elasticsearch.client.internal.node.NodeClient
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate.DataStreamTemplate
import org.elasticsearch.cluster.metadata.{ComponentTemplate, ComposableIndexTemplate, Template}
import org.elasticsearch.common.compress.CompressedXContent
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.index.IndexNotFoundException
import org.joor.Reflect.{on, onClass}
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, TemplateName}
import tech.beshu.ror.es.DataStreamService
import tech.beshu.ror.es.DataStreamService.DataStreamSettings.*
import tech.beshu.ror.es.DataStreamService.{CreationResult, DataStreamSettings}
import tech.beshu.ror.es.services.DataStreamSettingsOps.*
import tech.beshu.ror.es.utils.XContentJsonParserFactory
import tech.beshu.ror.utils.TaskOps.Measure

import java.time.Clock
import scala.jdk.CollectionConverters.*

final class EsDataStreamService(client: NodeClient, jsonParserFactory: XContentJsonParserFactory)(using Clock)
  extends DataStreamService
    with Logging {

  override def checkDataStreamExists(dataStreamName: DataStreamName.Full): Task[Boolean] = execute {
    val request = new GetDataStreamAction.Request(List(dataStreamName.value.value).toArray)
    val action = GetDataStreamAction.INSTANCE
    client.executeT(action, request)
      .map {
        response => response.getDataStreams.asScala.exists(_.getDataStream.getName == dataStreamName.value.value)
      }
      .onErrorRecoverWith {
        case _: IndexNotFoundException => Task.pure(false)
      }
  }

  override protected def createDataStream(dataStreamName: DataStreamName.Full): Task[CreationResult] = execute {
    val request = new CreateDataStreamAction.Request(dataStreamName.value.value)
    val actionType = CreateDataStreamAction.INSTANCE
    client.executeAck(actionType, request).map(_.isAcknowledged).map(CreationResult.apply)
  }

  override protected def checkIndexLifecyclePolicyExists(policyId: NonEmptyString): Task[Boolean] = execute {
    val enhancedActionType = client.findActionUnsafe[ActionResponse]("cluster:admin/ilm/get")
    val request =
      onClass("org.elasticsearch.xpack.core.ilm.action.GetLifecycleAction$Request", enhancedActionType.classLoader)
        .create(Array(policyId.value)) // varargs
        .get[ActionRequest]

    client.executeT(enhancedActionType.action, request)
      .map { response =>
        on(response)
          .call("getPolicies")
          .get[java.util.List[Object]]
          .asScala
          .map { obj =>
            on(obj).call("getLifecyclePolicy").call("getName").get[String]
          }
          .toList
          .contains(policyId.value)
      }
      .onErrorRecoverWith {
        case _: ResourceNotFoundException => Task.pure(false)
      }
  }

  override protected def createIndexLifecyclePolicy(policy: DataStreamSettings.LifecyclePolicy): Task[CreationResult] = execute {
    val enhancedActionType = client.findActionUnsafe[AcknowledgedResponse]("cluster:admin/ilm/put")
    val parser = jsonParserFactory.create(policy.toJson)
    val lifecyclePolicy =
      onClass("org.elasticsearch.xpack.core.ilm.LifecyclePolicy", enhancedActionType.classLoader)
        .call("parse", parser, policy.id.value)
        .get[Object]

    val request =
      onClass("org.elasticsearch.xpack.core.ilm.action.PutLifecycleAction$Request", enhancedActionType.classLoader)
        .create(lifecyclePolicy)
        .get[ActionRequest]

    client.executeAck(enhancedActionType.action, request).map(_.isAcknowledged).map(CreationResult.apply)
  }

  override protected def checkComponentTemplateExists(templateName: TemplateName): Task[Boolean] = execute {
    val request = GetComponentTemplateAction.Request(templateName.value.value)
    val action = GetComponentTemplateAction.INSTANCE
    client.executeT(action, request)
      .map { response =>
        response
          .getComponentTemplates
          .asScala
          .keySet
          .contains(templateName.value.value)
      }
      .onErrorRecoverWith {
        case _: ResourceNotFoundException => Task.pure(false)
      }
  }

  override protected def createComponentTemplateForMappings(settings: ComponentTemplateMappings): Task[CreationResult] = execute {
    val request: PutComponentTemplateAction.Request = componentTemplateMappings(settings)
    val action = PutComponentTemplateAction.INSTANCE

    client.executeAck(action, request).map(_.isAcknowledged).map(CreationResult.apply)
  }

  override protected def createComponentTemplateForIndex(settings: ComponentTemplateSettings): Task[CreationResult] = execute {
    val request = componentTemplateIndexSettingsRequest(settings)
    val action = PutComponentTemplateAction.INSTANCE
    client.executeAck(action, request).map(_.isAcknowledged).map(CreationResult.apply)
  }

  override protected def checkIndexTemplateExists(templateName: TemplateName): Task[Boolean] = {
    val request = GetComposableIndexTemplateAction.Request(templateName.value.value)
    val action = GetComposableIndexTemplateAction.INSTANCE
    client
      .executeT(action, request)
      .map(_.indexTemplates.asScala.keySet.contains(templateName.value.value))
      .onErrorRecoverWith {
        case _: ResourceNotFoundException => Task.pure(false)
      }
  }

  override protected def createIndexTemplate(settings: IndexTemplateSettings): Task[CreationResult] = execute {
    val request = createIndexTemplateRequest(settings)
    val action = PutComposableIndexTemplateAction.INSTANCE
    client.executeAck(action, request).map(_.isAcknowledged).map(CreationResult.apply)
  }

  private def componentTemplateMappings(settings: ComponentTemplateMappings) = {
    val template = new Template(null, CompressedXContent(ujson.write(settings.mappingsJson)), null)
    val version: java.lang.Long = null
    val componentTemplate = new ComponentTemplate(template, version, settings.metadata.asInstanceOf[Map[String, Object]].asJava)
    new PutComponentTemplateAction.Request(settings.templateName.value.value).componentTemplate(componentTemplate).create(true)
  }

  private def componentTemplateIndexSettingsRequest(settings: ComponentTemplateSettings): PutComponentTemplateAction.Request = {
    val componentSettings: Settings = Settings.builder().loadFromMap(Map("index.lifecycle.name" -> settings.lifecyclePolicyId.value).asJava).build()
    val template = new Template(componentSettings, null, null)
    val version: java.lang.Long = null
    val componentTemplate = new ComponentTemplate(template, version, settings.metadata.asInstanceOf[Map[String, Object]].asJava)
    new PutComponentTemplateAction.Request(settings.templateName.value.value).componentTemplate(componentTemplate).create(true)
  }

  private def createIndexTemplateRequest(settings: IndexTemplateSettings) = {
    val indexTemplate = {
      ComposableIndexTemplate
        .builder()
        .indexPatterns(List(settings.dataStreamName.value.value).asJava)
        .componentTemplates(settings.componentTemplates.toList.map(_.value.value).asJava)
        .priority(500)
        .metadata(settings.metadata.asInstanceOf[Map[String, AnyRef]].asJava)
        .dataStreamTemplate(new DataStreamTemplate())
        .build()
    }
    new PutComposableIndexTemplateAction.Request(settings.templateName.value.value).indexTemplate(indexTemplate).create(true)
  }

  private def execute[A](value: => Task[A]) = Task(value).flatten

  private[EsDataStreamService] final class EnhancedActionType[T <: ActionResponse](val action: ActionType[T],
                                                                                   val classLoader: ClassLoader)

  extension (nodeClient: NodeClient) {
    def findActionUnsafe[T <: ActionResponse](actionName: String): EnhancedActionType[T] = {
      val (actionType, transportActionType) = client.supportedActions.find {
        case (actionType, _) => actionType.name() == actionName
      }.getOrElse(throw new IllegalStateException(s"Unable to find action type with name $actionName"))
      val classLoader = transportActionType.getClass.getClassLoader
      EnhancedActionType(actionType.asInstanceOf[ActionType[T]], classLoader)
    }

    def executeT[REQUEST <: ActionRequest, RESPONSE <: ActionResponse](action: ActionType[RESPONSE],
                                                                       request: REQUEST): Task[RESPONSE] = {
      for {
        response <- Task.measure(
          task = Task.delay(nodeClient.execute(action, request).actionGet()),
          logTimeMeasurement = duration => Task.delay {
            logger.debug(s"Action ${action.name()} request: ${request.toString}, taken ${duration.toMillis}ms")
          }
        )
        _ <- Task.delay {
          logger.debug(s"Action ${action.name()} response: ${response.toString}")
        }
      } yield response
    }

    def executeAck[REQUEST <: ActionRequest, RESPONSE <: AcknowledgedResponse](action: ActionType[RESPONSE],
                                                                               request: REQUEST): Task[RESPONSE] = {
      executeT(action, request)
        .tapEval(response => Task.delay(logger.debug(s"Action ${action.name()} acknowledged: ${response.isAcknowledged}")))
    }

    private def supportedActions: Map[ActionType[ActionResponse], TransportAction[ActionRequest, ActionResponse]] = {
      on(nodeClient)
        .get[java.util.Map[ActionType[ActionResponse], TransportAction[ActionRequest, ActionResponse]]]("actions")
        .asScala
        .toMap
    }

  }
}
