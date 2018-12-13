package tech.beshu.ror.acl.requestcontext

import tech.beshu.ror.commons.aDomain.{Header, UnresolvedAddress}
import tech.beshu.ror.commons.domain.{LoggedUser, VariablesResolver}
import com.softwaremill.sttp.Method

trait RequestContext extends VariablesResolver {
  def getAction: String
  def getHeaders: Set[Header]
  def getRemoteAddress: UnresolvedAddress
  def getMethod: Method

  def setLoggedInUser(user: LoggedUser): Unit
}
