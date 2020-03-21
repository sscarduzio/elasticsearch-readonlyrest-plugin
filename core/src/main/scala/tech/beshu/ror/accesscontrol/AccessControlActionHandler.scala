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
package tech.beshu.ror.accesscontrol

import cats.implicits._
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.ForbiddenByMismatched.Cause
import tech.beshu.ror.accesscontrol.AccessControlActionHandler.ForbiddenCause
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.Outcome
import tech.beshu.ror.accesscontrol.domain.Operation

trait AccessControlActionHandler {
  def onAllow[T <: Operation](blockContext: BlockContext[T]): Unit
  def onForbidden(causes: java.util.List[ForbiddenCause]): Unit
  def onError(t: Throwable): Unit
  def onPassThrough(): Unit
}

object AccessControlActionHandler {
  sealed trait ForbiddenCause {
    def stringify: String
  }
  case object ForbiddenBlockMatch extends ForbiddenCause {
    override def stringify: String = "FORBIDDEN_BY_BLOCK"
  }
  case object OperationNotAllowed extends ForbiddenCause {
    override def stringify: String = "OPERATION_NOT_ALLOWED"
  }
  case object ImpersonationNotSupported extends ForbiddenCause {
    override def stringify: String = "IMPERSONATION_NOT_SUPPORTED"
  }
  case object ImpersonationNotAllowed extends ForbiddenCause {
    override def stringify: String = "IMPERSONATION_NOT_ALLOWED"
  }

  def fromMismatchedCause(cause: Cause): ForbiddenCause = {
    cause match {
      case Cause.OperationNotAllowed => OperationNotAllowed
      case Cause.ImpersonationNotSupported => ImpersonationNotSupported
      case Cause.ImpersonationNotAllowed => ImpersonationNotAllowed
    }
  }
}

// todo: do we need it?
object BlockContextRawDataHelper {

  def responseHeadersFrom[T <: Operation](blockContext: BlockContext[T]): Map[String, String] = {
    blockContext
      .responseHeaders
      .map(h => (h.name.value.value, h.value.value))
      .toMap
  }

  def contextHeadersFrom[T <: Operation](blockContext: BlockContext[T]): Map[String, String] = {
    blockContext
      .contextHeaders
      .map { h => (h.name.value.value, h.value.value) }
      .toMap
  }

  def indicesFrom[T <: Operation](blockContext: BlockContext[T]): Outcome[Set[String]] = {
    blockContext.indices.map(_.map(_.value.value))
  }

  def repositoriesFrom[T <: Operation](blockContext: BlockContext[T]): Set[String] = {
    blockContext.repositories match {
      case BlockContext.Outcome.Exist(repositories) => repositories.map(_.value.value)
      case BlockContext.Outcome.NotExist => Set.empty
    }
  }

  def snapshotsFrom[T <: Operation](blockContext: BlockContext[T]): Set[String] = {
    blockContext.snapshots match {
      case BlockContext.Outcome.Exist(snapshots) => snapshots.map(_.value.value)
      case BlockContext.Outcome.NotExist => Set.empty
    }
  }
}
