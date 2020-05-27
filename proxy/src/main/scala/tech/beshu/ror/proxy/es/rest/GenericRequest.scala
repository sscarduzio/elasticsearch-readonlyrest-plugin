package tech.beshu.ror.proxy.es.rest

import org.elasticsearch.action.{ActionRequest, ActionRequestValidationException}
import org.elasticsearch.rest.RestRequest

class GenericRequest(val rest: RestRequest) extends ActionRequest {
  override def validate(): ActionRequestValidationException = null
}
