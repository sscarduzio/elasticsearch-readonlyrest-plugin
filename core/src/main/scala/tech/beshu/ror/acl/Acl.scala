package tech.beshu.ror.acl

import cats.data.NonEmptySet
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.IndexName
import tech.beshu.ror.acl.blocks.Block.{ExecutionResult, History}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.request.RequestContext

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
  def writeResponseHeaders(headers: Map[NonEmptyString, NonEmptyString]): Unit
  def writeToThreadContextHeader(key: NonEmptyString, value: NonEmptyString): Unit
  def writeIndices(indices: NonEmptySet[IndexName]): Unit
  def writeSnapshots(indices: NonEmptySet[IndexName]): Unit
  def writeRepositories(indices: NonEmptySet[IndexName]): Unit
}