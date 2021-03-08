/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.template.get.GetComposableIndexTemplateAction
import org.elasticsearch.client.indices.{GetComposableIndexTemplateRequest, GetComposableIndexTemplatesResponse}

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
}
