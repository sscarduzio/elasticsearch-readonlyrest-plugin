package tech.beshu.ror.acl.request

import java.net.URI

import com.softwaremill.sttp.{Method, Uri}
import squants.information.{Bytes, Information}
import tech.beshu.ror.commons.aDomain.Header.Name
import tech.beshu.ror.commons.aDomain.{Action, Address, Header, Type}
import tech.beshu.ror.commons.shims.request.RequestInfoShim

import scala.collection.JavaConverters._

class EsRequestContext(rInfo: RequestInfoShim) extends RequestContext {

  override def id: RequestContext.Id = RequestContext.Id(rInfo.extractId())

  override def action: Action = Action(rInfo.extractAction)

  override def headers: Set[Header] =
    rInfo
      .extractRequestHeaders.asScala
      .map { case (name, value) => Header(Name(name), value) }
      .toSet

  override def remoteAddress: Address = Address(rInfo.extractRemoteAddress())

  override def localAddress: Address = Address(rInfo.extractLocalAddress())

  override def method: Method = Method(rInfo.extractMethod())

  override def uri: Uri = Uri(new URI(rInfo.extractURI()))

  override def contentLength: Information = Bytes(rInfo.extractContentLength().toLong)

  override def isReadOnlyRequest: Boolean = rInfo.extractIsReadRequest()

  override def `type`: Type = Type(rInfo.extractType())

  override def content: String = rInfo.extractContent()
}
