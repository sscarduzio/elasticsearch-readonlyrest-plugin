/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.template.delete.DeleteComponentTemplateAction
import org.elasticsearch.client.indices.DeleteComponentTemplateRequest

object DeleteComponentTemplate {

  implicit class DeleteComponentTemplateRequestOps(val request: DeleteComponentTemplateAction.Request) extends AnyVal {
    def toDeleteComponentTemplateRequest: DeleteComponentTemplateRequest = {
      new DeleteComponentTemplateRequest(request.name())
    }
  }
}
