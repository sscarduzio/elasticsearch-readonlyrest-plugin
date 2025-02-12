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
import org.elasticsearch.action.admin.indices.template.put.{PutComponentTemplateAction, PutComposableIndexTemplateAction}
import org.elasticsearch.action.support.TransportAction
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.action.{ActionRequest, ActionResponse, ActionType}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate.DataStreamTemplate
import org.elasticsearch.cluster.metadata.{ComponentTemplate, ComposableIndexTemplate, DataStream, Template}
import org.elasticsearch.common.compress.CompressedXContent
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.index.IndexNotFoundException
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, TemplateName}
import tech.beshu.ror.es.DataStreamService
import tech.beshu.ror.es.DataStreamService.{Capabilities, CreationResult}
import tech.beshu.ror.es.utils.XContentJsonParserFactory
import tech.beshu.ror.utils.ReflecUtils

import scala.jdk.CollectionConverters.*
import java.lang.reflect.Modifier
import java.time.Instant

final class EsDataStreamService(client: NodeClient, jsonParserFactory: XContentJsonParserFactory)
  extends DataStreamService
    with Logging {

  override def checkDataStreamExists(dataStreamName: DataStreamName.Full): Task[Boolean] = execute {
    val enhancedActionType = client.findActionUnsafe[ActionResponse]("indices:admin/data_stream/get")
    val requestClass = enhancedActionType.loadClass("org.elasticsearch.xpack.core.action.GetDataStreamAction$Request")
    val request =
      requestClass
        .getConstructor(classOf[Array[String]])
        .newInstance(List(dataStreamName.value.value).toArray)
        .asInstanceOf[ActionRequest]

    client
      .executeT(enhancedActionType.action, request)
      .map { response =>
        val dataStreams =
          ReflecUtils
            .invokeMethod(response, response.getClass, "getDataStreams")
            .asInstanceOf[java.util.List[Object]]
            .asScala
            .map(obj => ReflecUtils.invokeMethod(obj, obj.getClass, "getDataStream").asInstanceOf[DataStream])

        dataStreams.exists(dataStream => dataStream.getName == dataStreamName.value.value)
      }
      .onErrorRecoverWith {
        case _: IndexNotFoundException => Task.pure(false)
      }
  }

  override def createDataStream(dataStreamName: DataStreamName.Full): Task[CreationResult] = execute {
    val enhancedActionType = client.findActionUnsafe[AcknowledgedResponse]("indices:admin/data_stream/create")
    val createRequestClass = enhancedActionType.loadClass("org.elasticsearch.xpack.core.action.CreateDataStreamAction$Request")
    val request =
      createRequestClass
        .getConstructor(classOf[String])
        .newInstance(dataStreamName.value.value)
        .asInstanceOf[ActionRequest]

    client.executeAck(enhancedActionType.action, request).map(_.isAcknowledged).map(CreationResult.apply)
  }

  override def createIndexLifecyclePolicy(policyName: String, policyJson: ujson.Value): Task[CreationResult] = execute {
    val enhancedActionType = client.findActionUnsafe[AcknowledgedResponse]("cluster:admin/ilm/put")
    val parser = jsonParserFactory.create(policyJson)
    val lifecyclePolicyClass = enhancedActionType.loadClass("org.elasticsearch.xpack.core.ilm.LifecyclePolicy")
    val lifecyclePolicy =
      ReflecUtils
        .getMethodOf(lifecyclePolicyClass, Modifier.PUBLIC, "parse", 2)
        .invoke(null, parser, policyName)

    val lifecycleRequestClass =
      enhancedActionType.loadClass("org.elasticsearch.xpack.core.ilm.action.PutLifecycleAction$Request")

    val actionRequest: ActionRequest =
      lifecycleRequestClass
        .getConstructor(lifecyclePolicy.getClass)
        .newInstance(lifecyclePolicy)
        .asInstanceOf[ActionRequest]

    client.executeAck(enhancedActionType.action, actionRequest).map(_.isAcknowledged).map(CreationResult.apply)
  }

  override def createComponentTemplateForMappings(templateName: TemplateName,
                                                  mappingsJson: ujson.Value,
                                                  metadata: Map[String, String]): Task[CreationResult] = execute {
    val request: PutComponentTemplateAction.Request = componentTemplateMappings(templateName, mappingsJson, metadata)
    val action = PutComponentTemplateAction.INSTANCE

    client.executeAck(action, request).map(_.isAcknowledged).map(CreationResult.apply)
  }

  override def createComponentTemplateForIndex(templateName: TemplateName,
                                               lifecyclePolicyName: String,
                                               metadata: Map[String, String]): Task[CreationResult] = execute {
    val request = componentTemplateIndexSettingsRequest(templateName, lifecyclePolicyName, metadata)
    val action = PutComponentTemplateAction.INSTANCE
    client.executeAck(action, request).map(_.isAcknowledged).map(CreationResult.apply)
  }

  override def createIndexTemplate(templateName: TemplateName,
                                   dataStreamName: DataStreamName.Full,
                                   componentTemplates: NonEmptyList[TemplateName],
                                   metadata: Map[String, String]): Task[CreationResult] = execute {
    val request = createIndexTemplateRequest(templateName, dataStreamName, componentTemplates, metadata)
    val action = PutComposableIndexTemplateAction.INSTANCE
    client.executeAck(action, request).map(_.isAcknowledged).map(CreationResult.apply)
  }

  override val capabilities: DataStreamService.Capabilities = Capabilities(ilmMaxPrimaryShardSize = false)

  private def componentTemplateMappings(templateName: TemplateName,
                                        mappingsJson: ujson.Value,
                                        metadata: Map[String, String]) = {
    val template = new Template(null, CompressedXContent(ujson.write(mappingsJson)), null)
    val version: java.lang.Long = null
    val componentTemplate = new ComponentTemplate(template, version, metadata.asInstanceOf[Map[String, Object]].asJava)
    new PutComponentTemplateAction.Request(templateName.value.value).componentTemplate(componentTemplate).create(true)
  }

  private def componentTemplateIndexSettingsRequest(templateName: TemplateName,
                                                    lifecyclePolicyName: String,
                                                    metadata: Map[String, String]): PutComponentTemplateAction.Request = {
    val settings: Settings = Settings.builder().loadFromMap(Map("index.lifecycle.name" -> lifecyclePolicyName).asJava).build()
    val template = new Template(settings, null, null)
    val version: java.lang.Long = null
    val componentTemplate = new ComponentTemplate(template, version, metadata.asInstanceOf[Map[String, Object]].asJava)
    new PutComponentTemplateAction.Request(templateName.value.value).componentTemplate(componentTemplate).create(true)
  }

  private def createIndexTemplateRequest(templateName: TemplateName,
                                         dataStreamName: DataStreamName.Full,
                                         componentTemplates: NonEmptyList[TemplateName],
                                         metadata: Map[String, String]) = {
    val template: Template = null
    val priority: java.lang.Long = 500
    val version: java.lang.Long = null
    val indexTemplate = new ComposableIndexTemplate(
      List(dataStreamName.value.value).asJava,
      template,
      componentTemplates.toList.map(_.value.value).asJava,
      priority,
      version,
      metadata.asInstanceOf[Map[String, AnyRef]].asJava,
      new DataStreamTemplate()
    )
    new PutComposableIndexTemplateAction.Request(templateName.value.value).indexTemplate(indexTemplate).create(true)
  }

  private def execute[A](value: => Task[A]) = Task(value).flatten

  final class EnhancedActionType[T <: ActionResponse](val action: ActionType[T], classLoader: ClassLoader) {
    def loadClass(name: String): Class[_] = classLoader.loadClass(name)
  }

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
      Task {
        val t0 = Instant.now
        logger.debug(s"Action ${action.name()} request: ${request.toString}")
        val response = nodeClient.execute(action, request).actionGet()
        logger.debug(s"Action ${action.name()} response: ${response.toString}, taken ${Instant.now().minusMillis(t0.toEpochMilli).toEpochMilli}ms")
        response
      }
    }

    def executeAck[REQUEST <: ActionRequest, RESPONSE <: AcknowledgedResponse](action: ActionType[RESPONSE],
                                                                               request: REQUEST): Task[RESPONSE] = {
      executeT(action, request)
        .tapEval(response => Task.delay(s"Action ${action.name()} acknowledged: ${response.isAcknowledged}"))
    }

    private def supportedActions: Map[ActionType[ActionResponse], TransportAction[ActionRequest, ActionResponse]] = {
      val actions = ReflecUtils.getField(nodeClient, nodeClient.getClass, "actions")
      actions
        .asInstanceOf[java.util.Map[ActionType[ActionResponse], TransportAction[ActionRequest, ActionResponse]]]
        .asScala
        .toMap
    }

  }
}
