package tech.beshu.ror.acl.requestcontext

import tech.beshu.ror.commons.aDomain.{Action, Header, UnresolvedAddress}
import tech.beshu.ror.commons.domain.{LoggedUser, VariablesResolver}
import com.softwaremill.sttp.Method
import io.lemonlabs.uri.Uri

trait RequestContext extends VariablesResolver {
  def getAction: Action
  def getHeaders: Set[Header]
  def getRemoteAddress: UnresolvedAddress
  def getMethod: Method
  def getUri: Uri

  def getLoggedUser: Option[LoggedUser]
  def setLoggedInUser(user: LoggedUser): Unit
}
