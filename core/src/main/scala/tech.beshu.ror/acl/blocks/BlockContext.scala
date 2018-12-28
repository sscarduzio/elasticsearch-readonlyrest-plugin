package tech.beshu.ror.acl.blocks

import tech.beshu.ror.acl.blocks.RequestContextInitiatedBlockContext.BlockContextData
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.{Header, IndexName}
import tech.beshu.ror.commons.domain.LoggedUser

trait BlockContext {

  def loggedUser: Option[LoggedUser]
  def setLoggedUser(user: LoggedUser): BlockContext

  def responseHeaders: Set[Header]
  def addResponseHeader(header: Header): BlockContext
  def contextHeaders: Set[Header]
  def addContextHeader(header: Header): BlockContext
  def kibanaIndex: Option[IndexName]
  def setKibanaIndex(index: IndexName): BlockContext

}

class RequestContextInitiatedBlockContext private(val data: BlockContextData)
  extends BlockContext {

  override def loggedUser: Option[LoggedUser] = data.loggedUser

  override def setLoggedUser(user: LoggedUser): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(loggedUser = Some(user)))

  override def responseHeaders: Set[Header] = data.responseHeaders.toSet

  override def addResponseHeader(header: Header): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(responseHeaders = data.responseHeaders :+ header))

  override def contextHeaders: Set[Header] = data.contextHeaders.toSet

  override def addContextHeader(header: Header): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(contextHeaders = data.contextHeaders :+ header))

  override def kibanaIndex: Option[IndexName] = data.kibanaIndex

  override def setKibanaIndex(index: IndexName): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(kibanaIndex = Some(index)))
}

object RequestContextInitiatedBlockContext {

  final case class BlockContextData(loggedUser: Option[LoggedUser],
                                    responseHeaders: Vector[Header],
                                    contextHeaders: Vector[Header],
                                    kibanaIndex: Option[IndexName])

  def fromRequestContext(requestContext: RequestContext): RequestContextInitiatedBlockContext =
    new RequestContextInitiatedBlockContext(
      BlockContextData(
        loggedUser = None,
        responseHeaders = Vector.empty,
        contextHeaders = Vector.empty,
        kibanaIndex = None
      )
    )
}