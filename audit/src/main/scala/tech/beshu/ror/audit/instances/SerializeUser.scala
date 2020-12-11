package tech.beshu.ror.audit.instances

import tech.beshu.ror.audit.AuditRequestContext

object SerializeUser {

  def serialize(requestContext: AuditRequestContext): Option[String] = {
    requestContext.loggedInUserName.orElse(requestContext.attemptedUserName).orElse(requestContext.rawAuthHeader)
  }
}
