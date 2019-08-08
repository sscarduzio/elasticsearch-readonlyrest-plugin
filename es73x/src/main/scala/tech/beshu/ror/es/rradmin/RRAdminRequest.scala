package tech.beshu.ror.es.rradmin

import org.elasticsearch.action.{ActionRequest, ActionRequestValidationException}
import org.elasticsearch.rest.RestRequest
import tech.beshu.ror.adminapi.AdminRestApi

class RRAdminRequest(request: RestRequest) extends ActionRequest {

  def this() {
    this(null)
  }

  def getAdminRequest = AdminRestApi.AdminRequest(request.method.name, request.path, request.content.utf8ToString)

  override def validate() = new ActionRequestValidationException
}
