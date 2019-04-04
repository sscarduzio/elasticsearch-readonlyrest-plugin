/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.acl

import monix.eval.Task
import tech.beshu.ror.acl.AclHandlingResult.Result
import tech.beshu.ror.acl.blocks.Block.{ExecutionResult, History}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.request.RequestContext

import scala.util.Try

trait Acl {
  def handle(requestContext: RequestContext, handler: AclActionHandler): Task[AclHandlingResult]
}

trait AclHandlingResult {
  def history: Vector[History]
  def handlingResult: Result
  def commit(): Try[Unit]
}

object AclHandlingResult {
  sealed trait Result
  object Result {
    final case class Success(executionResult: ExecutionResult) extends Result
    final case class NotFound(ex: Throwable) extends Result
    final case class AclError(ex: Throwable) extends Result
  }
}

trait AclActionHandler {
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
  def commit(): Unit
}