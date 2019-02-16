package tech.beshu.ror.acl.request

import java.time.Instant

import com.softwaremill.sttp.Method
import eu.timepit.refined.types.string.NonEmptyString
import squants.information.{Bytes, Information}
import tech.beshu.ror.acl.aDomain
import tech.beshu.ror.acl.aDomain.Header.Name
import tech.beshu.ror.acl.aDomain._
import tech.beshu.ror.shims.request.RequestInfoShim

import scala.collection.JavaConverters._
import scala.util.Try

// fixme: maybe we don;'t need RequestInfoShim
class EsRequestContext private (rInfo: RequestInfoShim) extends RequestContext {

  override val timestamp: Instant =
    Instant.now()

  override val taskId: Long =
    rInfo.extractTaskId()

  override val id: RequestContext.Id =
  Option(rInfo.extractId)
    .map(RequestContext.Id.apply)
    .getOrElse(throw new IllegalArgumentException(s"Cannot create request ID"))

  override val action: Action =
    Option(rInfo.extractAction)
      .map(Action.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request action"))

  override val headers: Set[Header] =
    rInfo
      .extractRequestHeaders.asScala
      .flatMap { case (name, value) =>
        (NonEmptyString.unapply(name), NonEmptyString.unapply(value)) match {
          case (Some(headerName), Some(headerValue)) => Some(Header(Name(headerName), headerValue))
          case _ => None
        }
      }
      .toSet

  override val remoteAddress: Address =
    forceCreateAddressFrom(rInfo.extractRemoteAddress())

  override val localAddress: Address =
    forceCreateAddressFrom(rInfo.extractLocalAddress())

  override val method: Method =
    Option(rInfo.extractMethod)
      .map(Method.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request method"))

  override val uriPath: UriPath =
  Option(rInfo.extractURI)
    .map(UriPath.apply)
    .getOrElse(throw new IllegalArgumentException(s"Cannot create request URI path"))

  override val contentLength: Information =
    Bytes(rInfo.extractContentLength().toLong)

  override val `type`: Type =
    Option(rInfo.extractType)
      .map(Type.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request type"))

  override val content: String =
    Option(rInfo.extractContent()).getOrElse("")

  override val indices: Set[aDomain.IndexName] =
    rInfo.extractIndices().asScala.map(IndexName.apply).toSet

  override val allIndicesAndAliases: Set[aDomain.IndexName] =
    rInfo.extractAllIndicesAndAliases().asScala.map(IndexName.apply).toSet

  override val repositories: Set[IndexName] =
    rInfo.extractRepositories().asScala.map(IndexName.apply).toSet

  override val snapshots: Set[IndexName] =
    rInfo.extractSnapshots().asScala.map(IndexName.apply).toSet

  override val isReadOnlyRequest: Boolean =
    rInfo.extractIsReadRequest()

  override val involvesIndices: Boolean =
    rInfo.involvesIndices()

  override val isCompositeRequest: Boolean =
    rInfo.extractIsCompositeRequest()

  override val isAllowedForDLS: Boolean =
    rInfo.extractIsAllowedForDLS()

  private def forceCreateAddressFrom(value: String) = {
    Address.from(value).getOrElse(throw new IllegalArgumentException(s"Cannot create IP or hostname from $value"))
  }
}

object EsRequestContext {
  def from(rInfo: RequestInfoShim): Try[RequestContext] = Try(new EsRequestContext(rInfo))
}