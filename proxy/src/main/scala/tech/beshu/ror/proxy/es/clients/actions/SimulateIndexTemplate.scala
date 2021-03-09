/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.template.post.{SimulateIndexTemplateRequest => AdminSimulateIndexTemplateRequest, SimulateIndexTemplateResponse => AdminSimulateIndexTemplateResponse}
import org.elasticsearch.client.indices.{SimulateIndexTemplateRequest, SimulateIndexTemplateResponse}

object SimulateIndexTemplate {

  implicit class SimulateIndexTemplateRequestOps(val request: AdminSimulateIndexTemplateRequest) extends AnyVal {
    def toSimulateIndexTemplateRequest: SimulateIndexTemplateRequest = {
      import PutComposableTemplate._
      val req = new SimulateIndexTemplateRequest(request.getIndexName)
      Option(request.getIndexTemplateRequest).foreach { r =>
        req.indexTemplateV2Request(r.toPutComposableTemplateRequest)
      }
      req
    }
  }

  implicit class SimulateIndexTemplateResponseOps(val response: SimulateIndexTemplateResponse) extends AnyVal {
    def toSimulateIndexTemplateResponse: AdminSimulateIndexTemplateResponse = {
      new AdminSimulateIndexTemplateResponse(
        response.resolvedTemplate(),
        response.overlappingTemplates()
      )
    }
  }
}
