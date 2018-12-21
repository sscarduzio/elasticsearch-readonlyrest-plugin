package tech.beshu.ror.acl.request

import cats.Show
import cats.implicits._
import com.softwaremill.sttp.{Method, Uri}
import com.typesafe.scalalogging.StrictLogging
import squants.information.{Bytes, Information}
import tech.beshu.ror.acl.request.RequestContext.Id
import tech.beshu.ror.acl.request.RequestContextOps.toRequestContextOps
import tech.beshu.ror.commons.aDomain._
import tech.beshu.ror.commons.domain.{LoggedUser, VariablesResolver}
import tech.beshu.ror.commons.show.logs._

import scala.language.implicitConversions

trait RequestContext extends VariablesResolver {
  def id: Id
  def `type`: Type
  def action: Action
  def headers: Set[Header]
  def remoteAddress: Address
  def localAddress: Address
  def method: Method
  def uri: Uri
  def contentLength: Information
  def content: String

  def isReadOnlyRequest: Boolean

  def reset(): Unit
  def commit(): Unit
}

object RequestContext extends StrictLogging {
  final case class Id(value: String) extends AnyVal
  object Id {
    implicit val show: Show[Id] = Show.show(_.value)
  }

  // fixme:
  implicit val show: Show[RequestContext] = Show.show { r =>
//    def stringifyLoggedUser = r.loggedUser match {
//      case Some(user) => s"${user.id.show}"
//      case None => "[no basic auth header]"
//    }
    def stringifyContentLength = {
      if(r.contentLength == Bytes(0)) "<N/A>"
      else if(logger.underlying.isDebugEnabled()) r.content
      else s"<OMITTED, LENGTH=${r.contentLength}> "
    }
    s"""{
       | ID: ${r.id.show},
       | TYP: ${r.`type`.show},
       | CGR: ${r.currentGroup.show},
       | USR: //todo
       | BRS: ${r.headers.exists(_.name === Header.Name.userAgent)},
       | KDX: //todo,
       | ACT: ${r.action.show},
       | OA: ${r.remoteAddress.show},
       | DA: ${r.localAddress.show},
       | IDX: // todo,
       | MET: ${r.method.show},
       | PTH: ${r.uri.show},
       | CNT: $stringifyContentLength,
       | HDR: ${r.headers.map(_.show).mkString(", ")},
       | HIS: // todo
       | }""".stripMargin
  }
}

class RequestContextOps(val requestContext: RequestContext) extends AnyVal {

  def xForwardedForHeaderValue: Option[Address] = {
    findHeader(Header.Name.xForwardedFor)
      .flatMap { header =>
        Option(header.value)
          .flatMap(_.split(",").headOption)
          .map(Address.apply)
      }
  }

  def currentGroup: Group = {
    findHeader(Header.Name.currentGroup) match {
      case None => Group.`N/A`
      case Some(Header(_, "")) => Group.Empty
      case Some(Header(_, value)) => Group.AGroup(value)
    }
  }

  private def findHeader(name: Header.Name) = requestContext.headers.find(_.name === name)
}

sealed trait Group
object Group {
  final case class AGroup(value: String) extends Group
  case object Empty extends Group
  case object `N/A` extends Group

  implicit val show: Show[Group] = Show.show {
    case AGroup(name) => name
    case Empty => "<empty>"
    case `N/A` => "N/A"
  }
}

object RequestContextOps {
  implicit def toRequestContextOps(rc: RequestContext): RequestContextOps = new RequestContextOps(rc)
}