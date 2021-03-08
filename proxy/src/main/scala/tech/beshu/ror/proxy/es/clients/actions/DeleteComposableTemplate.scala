/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.template.delete.DeleteComposableIndexTemplateAction
import org.elasticsearch.client.indices.DeleteComposableIndexTemplateRequest

object DeleteComposableTemplate {

  implicit class DeleteComposableTemplateRequestOps(val request: DeleteComposableIndexTemplateAction.Request) extends AnyVal {
    def toDeleteComposableTemplateRequest: DeleteComposableIndexTemplateRequest = {
      new DeleteComposableIndexTemplateRequest(request.name())
    }
  }
}
