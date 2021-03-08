/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.template.put.PutComposableIndexTemplateAction
import org.elasticsearch.client.indices.PutComposableIndexTemplateRequest

object PutComposableTemplate {

  implicit class PutComposableTemplateRequestOps(val request: PutComposableIndexTemplateAction.Request) extends AnyVal {
    def toPutComposableTemplateRequest: PutComposableIndexTemplateRequest = {
      val req = new PutComposableIndexTemplateRequest()
      req.name(request.name())
      req.indexTemplate(request.indexTemplate())
      req.create(request.create())
      req.cause(request.cause())
    }
  }

}
