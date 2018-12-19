package tech.beshu.ror.acl.request

import com.softwaremill.sttp.Method
import io.lemonlabs.uri.Uri
import squants.information.Information
import tech.beshu.ror.commons.aDomain
import tech.beshu.ror.commons.domain.LoggedUser
import tech.beshu.ror.commons.shims.request.RequestInfoShim

class EsRequestContext(rInfo: RequestInfoShim) extends RequestContext {

  override def action: aDomain.Action = ???

  override def headers: Set[aDomain.Header] = ???

  override def remoteAddress: aDomain.Address = ???

  override def localAddress: aDomain.Address = ???

  override def method: Method = ???

  override def uri: Uri = ???

  override def contentLength: Information = ???

  override def isReadOnlyRequest: Boolean = ???

  override def loggedUser: Option[LoggedUser] = ???

  override def setLoggedInUser(user: LoggedUser): Unit = ???

  override def setKibanaIndex(index: aDomain.IndexName): Unit = ???

  override def setResponseHeader(header: aDomain.Header): Unit = ???

  override def setContextHeader(header: aDomain.Header): Unit = ???

  override def resolve(value: String): Option[String] = ???

  override def id: RequestContext.Id = ???

  override def reset(): Unit = ???

  override def commit(): Unit = ???
}
