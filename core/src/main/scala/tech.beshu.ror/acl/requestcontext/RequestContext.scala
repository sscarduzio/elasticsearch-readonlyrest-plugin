package tech.beshu.ror.acl.requestcontext

import tech.beshu.ror.commons.domain.LoggedUser

trait RequestContext {
  def getAction: String
  def getHeaders: Map[String, String]

  def setLoggedInUser(user: LoggedUser): Unit
}
