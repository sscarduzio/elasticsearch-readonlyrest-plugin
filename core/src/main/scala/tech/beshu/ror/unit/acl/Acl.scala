package tech.beshu.ror.unit.acl

import monix.eval.Task
import tech.beshu.ror.unit.acl.blocks.Block.{ExecutionResult, History}
import tech.beshu.ror.unit.acl.blocks.BlockContext
import tech.beshu.ror.unit.acl.request.RequestContext

trait Acl {
  def handle(requestContext: RequestContext, handler: AclHandler): Task[(Vector[History], ExecutionResult)]
  def involvesFilter: Boolean
  def doesRequirePassword: Boolean
}

trait AclHandler {
  def onForbidden(): Unit
  def onAllow(blockContext: BlockContext): ResponseWriter
  def isNotFound(t: Throwable): Boolean
  def onNotFound(t: Throwable): Unit
  def onError(t: Throwable): Unit
}

trait ResponseWriter {
  def writeResponseHeaders(headers: Map[String, String]): Unit
  def writeToThreadContextHeader(key: String, value: String): Unit
}