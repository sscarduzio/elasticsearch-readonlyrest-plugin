package tech.beshu.ror.acl.request

import java.net.URI

import com.softwaremill.sttp.{Method, Uri}
import eu.timepit.refined.types.string.NonEmptyString
import squants.information.{Bytes, Information}
import tech.beshu.ror.acl.aDomain
import tech.beshu.ror.acl.aDomain.Header.Name
import tech.beshu.ror.acl.aDomain._
import tech.beshu.ror.commons.shims.request.RequestInfoShim

import scala.collection.JavaConverters._

// fixme: maybe we don;'t need RequestInfoShim
// todo: eg. current group (should be check if there is single value - where we should place the check?
class EsRequestContext(rInfo: RequestInfoShim) extends RequestContext {

  override def id: RequestContext.Id = RequestContext.Id(rInfo.extractId())

  override def action: Action = Action(rInfo.extractAction)

  override def headers: Set[Header] =
    rInfo
      .extractRequestHeaders.asScala
      .flatMap { case (name, value) =>
        (NonEmptyString.unapply(name), NonEmptyString.unapply(value)) match {
          case (Some(headerName), Some(headerValue)) => Some(Header(Name(headerName), headerValue))
          case _ => None
        }
      }
      .toSet

  override def remoteAddress: Address = Address(rInfo.extractRemoteAddress())

  override def localAddress: Address = Address(rInfo.extractLocalAddress())

  override def method: Method = Method(rInfo.extractMethod())

  override def uri: Uri = Uri(new URI(rInfo.extractURI()))

  override def contentLength: Information = Bytes(rInfo.extractContentLength().toLong)

  override def isReadOnlyRequest: Boolean = rInfo.extractIsReadRequest()

  override def `type`: Type = Type(rInfo.extractType())

  override def content: String = rInfo.extractContent()

  override def indices: Set[aDomain.IndexName] = rInfo.extractIndices().asScala.map(IndexName.apply).toSet

  override def allIndicesAndAliases: Set[aDomain.IndexName] = rInfo.extractAllIndicesAndAliases().asScala.map(IndexName.apply).toSet

  override def involvesIndices: Boolean = rInfo.involvesIndices()

  override def isCompositeRequest: Boolean = rInfo.extractIsCompositeRequest()
}
