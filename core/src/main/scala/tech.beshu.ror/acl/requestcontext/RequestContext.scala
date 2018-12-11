package tech.beshu.ror.acl.requestcontext

import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.domain.LoggedUser

trait RequestContext {
  def getAction: String
  def getHeaders: Set[Header]
  def setLoggedInUser(user: LoggedUser): Unit
}
