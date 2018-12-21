package tech.beshu.ror.acl.blocks

import tech.beshu.ror.acl.blocks.RequestContextInitiatedBlockContext.BlockContextData
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.{Header, IndexName}
import tech.beshu.ror.commons.domain.LoggedUser

trait BlockContext {

  def loggedUser: Option[LoggedUser]
  def setLoggedUser(user: LoggedUser): BlockContext

  def setResponseHeader(header: Header): BlockContext
  def setContentHeader(header: Header): BlockContext
  def setKibanaIndex(index: IndexName): BlockContext

}

class RequestContextInitiatedBlockContext private(val data: BlockContextData)
  extends BlockContext {

  override def loggedUser: Option[LoggedUser] = data.loggedUser

  override def setLoggedUser(user: LoggedUser): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(loggedUser = Some(user)))

  override def setResponseHeader(header: Header): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(responseHeaders = data.responseHeaders :+ header))

  override def setContentHeader(header: Header): BlockContext = ???

  override def setKibanaIndex(index: IndexName): BlockContext = ???
}

object RequestContextInitiatedBlockContext {

  final case class BlockContextData(loggedUser: Option[LoggedUser],
                                    responseHeaders: Vector[Header])

  def fromRequestContext(requestContext: RequestContext): RequestContextInitiatedBlockContext =
    new RequestContextInitiatedBlockContext(
      BlockContextData(None, Vector.empty) // todo:
    )
}