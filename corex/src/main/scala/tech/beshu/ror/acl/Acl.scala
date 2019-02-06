package tech.beshu.ror.acl

import monix.eval.Task
import tech.beshu.ror.acl.blocks.Block.{ExecutionResult, History}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.request.RequestContext

trait Acl {
  def handle(requestContext: RequestContext, handler: AclHandler): Task[(Vector[History], ExecutionResult)]
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
  def writeIndices(indices: Set[String]): Unit
  def writeSnapshots(indices: Set[String]): Unit
  def writeRepositories(indices: Set[String]): Unit
}