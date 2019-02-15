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

// fixme: maybe we don;'t need RequestInfoShim
class EsRequestContext(rInfo: RequestInfoShim) extends RequestContext {

  override val timestamp: Instant = Instant.now()

  override val taskId: Long = rInfo.extractTaskId()

  override val id: RequestContext.Id = RequestContext.Id(rInfo.extractId())

  override val action: Action = Action(rInfo.extractAction)

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

  override val remoteAddress: Address = Address(rInfo.extractRemoteAddress())

  override val localAddress: Address = Address(rInfo.extractLocalAddress())

  override val method: Method = Method(rInfo.extractMethod())

  override val uriPath: UriPath = UriPath(rInfo.extractURI())

  override val contentLength: Information = Bytes(rInfo.extractContentLength().toLong)

  override val isReadOnlyRequest: Boolean = rInfo.extractIsReadRequest()

  override val `type`: Type = Type(rInfo.extractType())

  override val content: String = rInfo.extractContent()

  override val indices: Set[aDomain.IndexName] = rInfo.extractIndices().asScala.map(IndexName.apply).toSet

  override val allIndicesAndAliases: Set[aDomain.IndexName] = rInfo.extractAllIndicesAndAliases().asScala.map(IndexName.apply).toSet

  override val repositories: Set[IndexName] = rInfo.extractRepositories().asScala.map(IndexName.apply).toSet

  override val snapshots: Set[IndexName] = rInfo.extractSnapshots().asScala.map(IndexName.apply).toSet

  override val involvesIndices: Boolean = rInfo.involvesIndices()

  override val isCompositeRequest: Boolean = rInfo.extractIsCompositeRequest()

  override val isAllowedForDLS: Boolean = rInfo.extractIsAllowedForDLS()
}
