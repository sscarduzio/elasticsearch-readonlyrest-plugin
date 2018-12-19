package tech.beshu.ror.acl.request

import cats.Show
import cats.implicits._
import com.softwaremill.sttp.Method
import io.lemonlabs.uri.Uri
import squants.information.Information
import tech.beshu.ror.acl.request.RequestContext.Id
import tech.beshu.ror.commons.aDomain.{Action, Address, Header, IndexName}
import tech.beshu.ror.commons.domain.{LoggedUser, VariablesResolver}

import scala.language.implicitConversions

trait RequestContext extends VariablesResolver {
  def id: Id
  def action: Action
  def headers: Set[Header]
  def remoteAddress: Address
  def localAddress: Address
  def method: Method
  def uri: Uri
  def contentLength: Information

  def isReadOnlyRequest: Boolean

  def loggedUser: Option[LoggedUser]

  def setLoggedInUser(user: LoggedUser): Unit
  def setKibanaIndex(index: IndexName): Unit
  def setResponseHeader(header: Header): Unit
  def setContextHeader(header: Header): Unit

  def reset(): Unit
  def commit(): Unit
}

object RequestContext {
  final case class Id(value: String) extends AnyVal
  object Id {
    implicit val show: Show[Id] = Show.show(_.value)
  }

  implicit val show: Show[RequestContext] = ??? // todo: implement
}

class RequestContextOps(val requestContext: RequestContext) extends AnyVal {

  def xForwardedForHeaderValue: Option[Address] = {
    requestContext.headers
      .find(_.name === Header.Name.xForwardedFor)
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