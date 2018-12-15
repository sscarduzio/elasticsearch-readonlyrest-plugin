package tech.beshu.ror.acl.requestcontext

import tech.beshu.ror.commons.aDomain.{Action, Header, Address}
import tech.beshu.ror.commons.domain.{LoggedUser, VariablesResolver}
import com.softwaremill.sttp.Method
import io.lemonlabs.uri.Uri

import scala.language.implicitConversions

trait RequestContext extends VariablesResolver {
  def action: Action
  def headers: Set[Header]
  def remoteAddress: Address
  def method: Method
  def uri: Uri

  def loggedUser: Option[LoggedUser]
  def setLoggedInUser(user: LoggedUser): Unit
}

class RequestContextOps(val requestContext: RequestContext) extends AnyVal {

  def xForwardedForHeaderValue: Option[Address] = {
    requestContext.headers
      .find(_.name == "X-Forwarded-For")
      .flatMap { header =>
        Option(header.value)
          .flatMap(_.split(",").headOption)
          .map(Address.apply)
      }
  }
}

object RequestContextOps {
  implicit def toRequestContextOps(rc: RequestContext): RequestContextOps = new RequestContextOps(rc)
}