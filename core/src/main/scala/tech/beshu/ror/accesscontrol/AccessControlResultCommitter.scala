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
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.ForbiddenByMismatched.Cause
import tech.beshu.ror.accesscontrol.AccessControlActionHandler.{ForbiddenBlockMatch, ForbiddenCause}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.utils.LoggerOps._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object AccessControlResultCommitter extends Logging {

  // todo: to remove?
  def commit(result: RegularRequestResult, handler: AccessControlActionHandler): Unit = {
    Try {
      result match {
        case RegularRequestResult.Allow(blockContext, _) =>
          handler.onAllow(blockContext)
        case RegularRequestResult.ForbiddenBy(_, _) =>
          handler.onForbidden(List[ForbiddenCause](ForbiddenBlockMatch).asJava)
        case RegularRequestResult.ForbiddenByMismatched(causes) =>
          handler.onForbidden(causes.toList.map(AccessControlActionHandler.fromMismatchedCause).asJava)
        case RegularRequestResult.Failed(ex) =>
          handler.onError(ex)
        case RegularRequestResult.PassedThrough =>
          handler.onPassThrough()
      }
    }
  } match {
    case Success(_) =>
    case Failure(ex) =>
      logger.errorEx("ACL committing result failure", ex)
  }
}

trait AccessControlActionHandler {
  def onAllow(blockContext: BlockContext): Unit
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
  case object OpetationNotAllowed extends ForbiddenCause {
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
      case Cause.OperationNotAllowed => OpetationNotAllowed
      case Cause.ImpersonationNotSupported => ImpersonationNotSupported
      case Cause.ImpersonationNotAllowed => ImpersonationNotAllowed
    }
  }
}

object BlockContextJavaHelper {

  def responseHeadersFrom(blockContext: BlockContext): Map[String, String] = {
    blockContext
      .responseHeaders
      .map(h => (h.name.value.value, h.value.value))
      .toMap
  }

  def contextHeadersFrom(blockContext: BlockContext): Map[String, String] = {
    blockContext
      .contextHeaders
      .map { h => (h.name.value.value, h.value.value) }
      .toMap
  }

  def indicesFrom(blockContext: BlockContext): Set[String] = {
    blockContext.indices match {
      case BlockContext.Outcome.Exist(indices) => indices.map(_.value.value)
      case BlockContext.Outcome.NotExist => Set.empty
    }
  }

  def repositoriesFrom(blockContext: BlockContext): Set[String] = {
    blockContext.repositories match {
      case BlockContext.Outcome.Exist(repositories) => repositories.map(_.value.value)
      case BlockContext.Outcome.NotExist => Set.empty
    }
  }

  def snapshotsFrom(blockContext: BlockContext): Set[String] = {
    blockContext.snapshots match {
      case BlockContext.Outcome.Exist(snapshots) => snapshots.map(_.value.value)
      case BlockContext.Outcome.NotExist => Set.empty
    }
  }
}
