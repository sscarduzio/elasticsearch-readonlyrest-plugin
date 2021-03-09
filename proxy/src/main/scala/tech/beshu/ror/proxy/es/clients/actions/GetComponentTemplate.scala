/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.ElasticsearchStatusException
import org.elasticsearch.action.admin.indices.template.get.GetComponentTemplateAction
import org.elasticsearch.client.indices.{GetComponentTemplatesRequest, GetComponentTemplatesResponse}
import org.elasticsearch.cluster.metadata.ComponentTemplate
import tech.beshu.ror.proxy.es.clients.actions.utils.ElasticsearchStatusExceptionOps._
import scala.collection.JavaConverters._

object GetComponentTemplate {

  implicit class GetComponentTemplateRequestOps(val request: GetComponentTemplateAction.Request) extends AnyVal {
    def toGetComponentTemplateRequest: GetComponentTemplatesRequest = {
      new GetComponentTemplatesRequest(request.name())
    }
  }

  implicit class GetComponentTemplateResponseOps(val response: GetComponentTemplatesResponse) extends AnyVal {
    def toGetComponentTemplateResponse: GetComponentTemplateAction.Response = {
      new GetComponentTemplateAction.Response(response.getComponentTemplates)
    }
  }

  def notFoundResponseOf(request: GetComponentTemplateAction.Request): PartialFunction[Throwable, GetComponentTemplateAction.Response] = {
    case ex: ElasticsearchStatusException if ex.isNotFound && isAllTemplatesRequest(request) =>
      new GetComponentTemplateAction.Response(Map.empty[String, ComponentTemplate].asJava)
  }

  private def isAllTemplatesRequest(request: GetComponentTemplateAction.Request) = {
    Option(request.name()) match {
      case None | Some("*") => true
      case Some(_) => false
    }
  }
}
