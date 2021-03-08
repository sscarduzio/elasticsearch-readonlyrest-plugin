/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.template.get.GetComponentTemplateAction
import org.elasticsearch.client.indices.{GetComponentTemplatesRequest, GetComponentTemplatesResponse}

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
}
