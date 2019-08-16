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

import cats.data._
import cats.implicits._
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.AclActionHandler.{ForbiddenBlockMatch, ForbiddenCause}
import tech.beshu.ror.acl.AclHandlingResult.Result
import tech.beshu.ror.acl.AclHandlingResult.Result.ForbiddenByMismatched.Cause
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.domain.Header.Name
import tech.beshu.ror.acl.domain.{Header, IndexName}
import tech.beshu.ror.acl.headerValues._
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.utils.LoggerOps._

import scala.collection.immutable.SortedSet
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object AclResultCommitter extends Logging {

  def commit(result: AclHandlingResult, handler: AclActionHandler): Unit = {
    Try {
      result.handlingResult match {
        case Result.Allow(blockContext, _) =>
          handler.onAllow(blockContext)
        case Result.ForbiddenBy(_, _) =>
          handler.onForbidden(List[ForbiddenCause](ForbiddenBlockMatch).asJava)
        case Result.ForbiddenByMismatched(causes) =>
          handler.onForbidden(causes.toList.map(AclActionHandler.fromMismatchedCause).asJava)
        case Result.Failed(ex) =>
          handler.onError(ex)
        case Result.PassedThrough =>
          handler.onPassThrough()
      }
    }
  } match {
    case Success(_) =>
    case Failure(ex) =>
      logger.errorEx("ACL committing result failure", ex)
  }
}

trait AclActionHandler {
  def onAllow(blockContext: BlockContext): Unit
  def onForbidden(causes: java.util.List[ForbiddenCause]): Unit
  def onError(t: Throwable): Unit
  def onPassThrough(): Unit
}

object AclActionHandler {
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
    val responseHeaders =
      blockContext.responseHeaders ++ userRelatedHeadersFrom(blockContext) ++
        kibanaIndexHeaderFrom(blockContext).toSet ++ currentGroupHeaderFrom(blockContext).toSet ++
        availableGroupsHeaderFrom(blockContext).toSet
    if (responseHeaders.nonEmpty) responseHeaders.map(h => (h.name.value.value, h.value.value)).toMap
    else Map.empty
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
    NonEmptySet.fromSet(SortedSet.empty[IndexName] ++ blockContext.repositories) match {
      case Some(indices) => indices.toSortedSet.map(_.value.value)
      case None => Set.empty
    }
  }

  def snapshotsFrom(blockContext: BlockContext): Set[String] = {
    NonEmptySet.fromSet(SortedSet.empty[IndexName] ++ blockContext.snapshots) match {
      case Some(indices) => indices.toSortedSet.map(_.value.value)
      case None => Set.empty
    }
  }

  private def userRelatedHeadersFrom(blockContext: BlockContext): Option[Header] = {
    blockContext.loggedUser.map(user => Header(Name.rorUser, user.id))
  }

  private def kibanaIndexHeaderFrom(blockContext: BlockContext): Option[Header] = {
    blockContext.kibanaIndex.map(i => Header(Name.kibanaIndex, i))
  }

  private def currentGroupHeaderFrom(blockContext: BlockContext): Option[Header] = {
    blockContext.currentGroup.map(r => Header(Name.currentGroup, r))
  }

  private def availableGroupsHeaderFrom(blockContext: BlockContext): Option[Header] = {
    NonEmptyList
      .fromList(blockContext.availableGroups.toList)
      .map(groups => Header(Name.availableGroups, groups))
  }
}
