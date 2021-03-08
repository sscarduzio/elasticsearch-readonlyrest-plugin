/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.template.put.PutComponentTemplateAction
import org.elasticsearch.client.indices.PutComponentTemplateRequest

object PutComponentTemplate {

  implicit class PutComponentTemplateRequestOps(val request: PutComponentTemplateAction.Request) extends AnyVal {
    def toPutComponentTemplateRequest: PutComponentTemplateRequest = {
      val req = new PutComponentTemplateRequest()
      req.name(request.name())
      req.componentTemplate(request.componentTemplate())
      req.create(request.create())
      req.cause(request.cause())
    }
  }
}
