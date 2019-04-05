package tech.beshu.ror.acl.helpers

import cats.data._
import cats.implicits._
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.AclHandlingResult
import tech.beshu.ror.acl.AclHandlingResult.Result
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.domain.Header.Name
import tech.beshu.ror.acl.domain.{Header, IndexName}
import tech.beshu.ror.acl.headerValues._
import tech.beshu.ror.acl.orders._

import scala.collection.immutable.SortedSet
import scala.util.{Failure, Success, Try}

object AclResultCommitter extends Logging {

  def commit(result: AclHandlingResult, handler: AclActionHandler): Unit = {
    Try {
      result.handlingResult match {
        case Result.Allow(blockContext, _) =>
          handler.onAllow(blockContext)
        case Result.ForbiddenBy(_, _) | Result.ForbiddenByUnmatched =>
          handler.onForbidden()
        case Result.Failed(ex) =>
          handler.onError(ex)
      }
    }
  } match {
    case Success(_) =>
    case Failure(ex) =>
      logger.error("ACL committing result failure", ex)
  }
}

trait AclActionHandler {
  def onForbidden(): Unit
  def onAllow(blockContext: BlockContext): Unit
  def onError(t: Throwable): Unit
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
    NonEmptySet.fromSet(SortedSet.empty[IndexName] ++ blockContext.indices) match {
      case Some(indices) => indices.toSortedSet.map(_.value)
      case None => Set.empty
    }
  }

  def repositoriesFrom(blockContext: BlockContext): Set[String] = {
    NonEmptySet.fromSet(SortedSet.empty[IndexName] ++ blockContext.repositories) match {
      case Some(indices) => indices.toSortedSet.map(_.value)
      case None => Set.empty
    }
  }

  def snapshotsFrom(blockContext: BlockContext): Set[String] = {
    NonEmptySet.fromSet(SortedSet.empty[IndexName] ++ blockContext.snapshots) match {
      case Some(indices) => indices.toSortedSet.map(_.value)
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
    if (blockContext.availableGroups.isEmpty) None
    else Some(Header(Name.availableGroups, blockContext.availableGroups))
  }
}
