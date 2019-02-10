package tech.beshu.ror.acl.request

import cats.Show
import cats.implicits._
import com.softwaremill.sttp.{Method, Uri}
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.scala.Logging
import squants.information.{Bytes, Information}
import tech.beshu.ror.acl.blocks.{Block, BlockContext, VariablesManager, VariablesResolver}
import tech.beshu.ror.acl.request.RequestContext.Id
import tech.beshu.ror.acl.aDomain._
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.request.RequestContextOps.{BearerToken, RequestGroup}
import tech.beshu.ror.Constants
import RequestContextOps._

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
  def repositories: Set[IndexName]
  def snapshots: Set[IndexName]

  def isReadOnlyRequest: Boolean
  def involvesIndices: Boolean
  def isCompositeRequest: Boolean

  def variablesResolver: VariablesResolver = new VariablesManager(this)
}

object RequestContext extends Logging {

  final case class Id(value: String) extends AnyVal
  object Id {
    implicit val show: Show[Id] = Show.show(_.value)
  }

  def show(blockContext: Option[BlockContext],
           history: Vector[Block.History]): Show[RequestContext] =
    Show.show { r =>
      def stringifyLoggedUser = blockContext.flatMap(_.loggedUser) match {
        case Some(user) => s"${user.id.show}"
        case None => "[no basic auth header]"
      }

      def stringifyContentLength = {
        if (r.contentLength == Bytes(0)) "<N/A>"
        else if (logger.delegate.isEnabled(Level.DEBUG)) r.content
        else s"<OMITTED, LENGTH=${r.contentLength}> "
      }

      def stringifyIndices = {
        blockContext
          .toSet
          .flatMap { b: BlockContext => b.indices }
          .toList
          .map(_.show) match {
          case Nil => "<N/A>"
          case nel => nel.mkString(",")
        }
      }

      s"""{
         | ID:${r.id.show},
         | TYP:${r.`type`.show},
         | CGR:${r.currentGroup.show},
         | USR:$stringifyLoggedUser,
         | BRS:${r.headers.exists(_.name === Header.Name.userAgent)},
         | KDX:${blockContext.flatMap(_.kibanaIndex).getOrElse("null")},
         | ACT:${r.action.show},
         | OA:${r.remoteAddress.show},
         | XFF:${r.headers.find(_.name === Header.Name.xForwardedFor).map(_.value).getOrElse("null")},
         | DA:${r.localAddress.show},
         | IDX:$stringifyIndices,
         | MET:${r.method.show},
         | PTH:${r.uri.show},
         | CNT:$stringifyContentLength,
         | HDR:${r.headers.map(_.show).toList.sorted.mkString(", ")},
         | HIS:${history.map(_.show).mkString(", ")}
         | }""".stripMargin.replaceAll("\n", " ")
    }
}

class RequestContextOps(val requestContext: RequestContext) extends AnyVal {

  def xForwardedForHeaderValue: Option[Address] = {
    findHeader(Header.Name.xForwardedFor)
      .flatMap { header =>
        Option(header.value.value)
          .flatMap(_.split(",").headOption)
          .map(Address.apply)
      }
  }

  def currentGroup: RequestGroup = {
    findHeader(Header.Name.currentGroup) match {
      case None => RequestGroup.`N/A`
      case Some(Header(_, value)) => RequestGroup.AGroup(Group(value))
    }
  }

  def basicAuth: Option[BasicAuth] = {
    requestContext
      .headers
      .toStream
      .map(BasicAuth.fromHeader)
      .find(_.isDefined)
      .flatten
  }

  def bearerToken: Option[BearerToken] = {
    requestContext
      .headers
      .find(_.name === Header.Name.authorization)
      .flatMap { h =>
        val authMethodName = "Bearer "
        if (h.value.value.startsWith(authMethodName)) {
          NonEmptyString
            .unapply(h.value.value.substring(authMethodName.length))
            .map(BearerToken.apply)
        } else {
          None
        }
      }
  }

  def isRestMetadataPath: Boolean = requestContext.uri.toString().startsWith(Constants.REST_METADATA_PATH)

  private def findHeader(name: Header.Name) = requestContext.headers.find(_.name === name)
}

object RequestContextOps {
  implicit def toRequestContextOps(rc: RequestContext): RequestContextOps = new RequestContextOps(rc)

  sealed trait RequestGroup
  object RequestGroup {
    final case class AGroup(userGroup: Group) extends RequestGroup
    case object `N/A` extends RequestGroup

    implicit val show: Show[RequestGroup] = Show.show {
      case AGroup(group) => group.value.value
      case `N/A` => "N/A"
    }
  }

  final case class BearerToken(value: NonEmptyString)
}

