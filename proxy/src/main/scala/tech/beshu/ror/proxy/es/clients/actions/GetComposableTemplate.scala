/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.ElasticsearchStatusException
import org.elasticsearch.action.admin.indices.template.get.GetComposableIndexTemplateAction
import org.elasticsearch.client.indices.{GetComposableIndexTemplateRequest, GetComposableIndexTemplatesResponse}
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate
import tech.beshu.ror.proxy.es.clients.actions.utils.ElasticsearchStatusExceptionOps._

import scala.collection.JavaConverters._

object GetComposableTemplate {

  implicit class GetComposableTemplateRequestOps(val request: GetComposableIndexTemplateAction.Request) extends AnyVal {
    def toGetComposableTemplateRequest: GetComposableIndexTemplateRequest = {
      new GetComposableIndexTemplateRequest(request.name())
    }
  }

  implicit class GetComposableTemplateResponseOps(val response: GetComposableIndexTemplatesResponse) extends AnyVal {
    def toGetComposableTemplateResponse: GetComposableIndexTemplateAction.Response = {
      new GetComposableIndexTemplateAction.Response(response.getIndexTemplates)
    }
  }

  def notFoundResponseOf(request: GetComposableIndexTemplateAction.Request): PartialFunction[Throwable, GetComposableIndexTemplateAction.Response] = {
    case ex: ElasticsearchStatusException if ex.isNotFound && isAllTemplatesRequest(request) =>
      new GetComposableIndexTemplateAction.Response(Map.empty[String, ComposableIndexTemplate].asJava)
  }

  private def isAllTemplatesRequest(request: GetComposableIndexTemplateAction.Request) = {
    Option(request.name()) match {
      case None | Some("*") => true
      case Some(_) => false
    }
  }
}
