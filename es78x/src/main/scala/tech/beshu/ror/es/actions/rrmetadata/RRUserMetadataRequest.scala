package tech.beshu.ror.es.actions.rrmetadata

import org.elasticsearch.action.{ActionRequest, ActionRequestValidationException}

class RRUserMetadataRequest extends ActionRequest {
  override def validate(): ActionRequestValidationException = null
}
