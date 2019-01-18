package tech.beshu.ror.acl.request

import cats.Show
import cats.implicits._
import com.softwaremill.sttp.{Method, Uri}
import squants.information.Information
import tech.beshu.ror.acl.blocks.VariablesManager
import tech.beshu.ror.acl.blocks.VariablesResolver
import tech.beshu.ror.acl.request.RequestContext.Id
import tech.beshu.ror.acl.aDomain._

import scala.language.implicitConversions

trait RequestContext {
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

  def indices: Set[IndexName]
  def allIndicesAndAliases: Set[IndexName]

  def isReadOnlyRequest: Boolean
  def involvesIndices: Boolean
  def isCompositeRequest: Boolean

  def variablesResolver: VariablesResolver = new VariablesManager(this)
}

object RequestContext {
  final case class Id(value: String) extends AnyVal
  object Id {
    implicit val show: Show[Id] = Show.show(_.value)
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

  def currentGroup: RequestGroup = {
    findHeader(Header.Name.currentGroup) match {
      case None => RequestGroup.`N/A`
      case Some(Header(_, "")) => RequestGroup.Empty
      case Some(Header(_, value)) => RequestGroup.AGroup(value)
    }
  }

  private def findHeader(name: Header.Name) = requestContext.headers.find(_.name === name)
}

sealed trait RequestGroup
object RequestGroup {
  final case class AGroup(value: String) extends RequestGroup
  case object Empty extends RequestGroup
  case object `N/A` extends RequestGroup

  implicit val show: Show[RequestGroup] = Show.show {
    case AGroup(name) => name
    case Empty => "<empty>"
    case `N/A` => "N/A"
  }
}

object RequestContextOps {
  implicit def toRequestContextOps(rc: RequestContext): RequestContextOps = new RequestContextOps(rc)
}