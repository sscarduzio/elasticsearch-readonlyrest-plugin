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
package tech.beshu.ror.acl.blocks

import cats.implicits._
import cats.data.NonEmptySet
import tech.beshu.ror.acl.blocks.BlockContext.Outcome
import tech.beshu.ror.acl.domain.{Group, Header, IndexName, JwtTokenPayload, LoggedUser}
import tech.beshu.ror.acl.blocks.RequestContextInitiatedBlockContext.BlockContextData
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps.RequestGroup._
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.acl.orders._

import scala.collection.SortedSet

trait BlockContext {

  def loggedUser: Option[LoggedUser]
  def withLoggedUser(user: LoggedUser): BlockContext

  def jsonToken: Option[JwtTokenPayload]
  def withJsonToken(token: JwtTokenPayload): BlockContext

  def currentGroup: Option[Group]
  def withCurrentGroup(group: Group): BlockContext

  def availableGroups: SortedSet[Group]
  def withAddedAvailableGroups(groups: NonEmptySet[Group]): BlockContext

  def responseHeaders: Set[Header]
  def withAddedResponseHeader(header: Header): BlockContext

  def contextHeaders: Set[Header]
  def withAddedContextHeader(header: Header): BlockContext

  def kibanaIndex: Option[IndexName]
  def withKibanaIndex(index: IndexName): BlockContext

  def indices: Outcome[Set[IndexName]]
  def withIndices(indices: Set[IndexName]): BlockContext

  def repositories: Set[IndexName]
  def withRepositories(indices: NonEmptySet[IndexName]): BlockContext

  def snapshots: Set[IndexName]
  def withSnapshots(indices: NonEmptySet[IndexName]): BlockContext
}

object BlockContext {
  sealed trait Outcome[+T] {
    def getOrElse[S >: T](default: => S): S = this match {
      case Outcome.Exist(value) => value
      case Outcome.NotExist => default
    }
  }
  object Outcome {
    final case class Exist[T](value: T) extends Outcome[T]
    case object NotExist extends Outcome[Nothing]
  }
}

object NoOpBlockContext extends BlockContext {
  override val loggedUser: Option[LoggedUser] = None
  override def withLoggedUser(user: LoggedUser): BlockContext = this

  override val jsonToken: Option[JwtTokenPayload] = None
  override def withJsonToken(token: JwtTokenPayload): BlockContext = this

  override val currentGroup: Option[Group] = None
  override def withCurrentGroup(group: Group): BlockContext = this

  override val availableGroups: SortedSet[Group] = SortedSet.empty
  override def withAddedAvailableGroups(groups: NonEmptySet[Group]): BlockContext = this

  override val responseHeaders: Set[Header] = Set.empty
  override def withAddedResponseHeader(header: Header): BlockContext = this

  override val contextHeaders: Set[Header] = Set.empty
  override def withAddedContextHeader(header: Header): BlockContext = this

  override val kibanaIndex: Option[IndexName] = None
  override def withKibanaIndex(index: IndexName): BlockContext = this

  override val indices: Outcome[Set[IndexName]] = Outcome.NotExist
  override def withIndices(indices: Set[IndexName]): BlockContext = this

  override val repositories: Set[IndexName] = Set.empty
  override def withRepositories(indices: NonEmptySet[IndexName]): BlockContext = this

  override val snapshots: Set[IndexName] = Set.empty
  override def withSnapshots(indices: NonEmptySet[IndexName]): BlockContext = this
}

class RequestContextInitiatedBlockContext private(val data: BlockContextData)
  extends BlockContext {

  override def loggedUser: Option[LoggedUser] = data.loggedUser

  override def withLoggedUser(user: LoggedUser): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(loggedUser = Some(user)))

  override def currentGroup: Option[Group] = data.currentGroup

  override def withCurrentGroup(group: Group): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(currentGroup = Some(group)))

  override def availableGroups: SortedSet[Group] = SortedSet.empty[Group] ++ data.availableGroups

  override def withAddedAvailableGroups(groups: NonEmptySet[Group]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(availableGroups = data.availableGroups ++ groups.toSortedSet))

  override def responseHeaders: Set[Header] = data.responseHeaders.toSet

  override def withAddedResponseHeader(header: Header): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(responseHeaders = data.responseHeaders :+ header))

  override def contextHeaders: Set[Header] = data.contextHeaders.toSet

  override def withAddedContextHeader(header: Header): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(contextHeaders = data.contextHeaders :+ header))

  override def kibanaIndex: Option[IndexName] = data.kibanaIndex

  override def withKibanaIndex(index: IndexName): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(kibanaIndex = Some(index)))

  override def indices: Outcome[Set[IndexName]] = data.indices

  override def withIndices(indices: Set[IndexName]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(indices = Outcome.Exist(indices)))

  override def repositories: Set[IndexName] = data.repositories

  override def withRepositories(repositories: NonEmptySet[IndexName]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(repositories = repositories.toSortedSet))

  override def snapshots: Set[IndexName] = data.snapshots

  override def withSnapshots(snapshots: NonEmptySet[IndexName]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(snapshots = snapshots.toSortedSet))

  override def jsonToken: Option[JwtTokenPayload] = data.jsonToken

  override def withJsonToken(token: JwtTokenPayload): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(jsonToken = Some(token)))
}

object RequestContextInitiatedBlockContext {

  final case class BlockContextData(loggedUser: Option[LoggedUser],
                                    currentGroup: Option[Group],
                                    availableGroups: Set[Group],
                                    responseHeaders: Vector[Header],
                                    contextHeaders: Vector[Header],
                                    kibanaIndex: Option[IndexName],
                                    indices: Outcome[Set[IndexName]],
                                    repositories: Set[IndexName],
                                    snapshots: Set[IndexName],
                                    jsonToken: Option[JwtTokenPayload])

  def fromRequestContext(requestContext: RequestContext): RequestContextInitiatedBlockContext =
    new RequestContextInitiatedBlockContext(
      BlockContextData(
        loggedUser = None,
        currentGroup = requestContext.currentGroup match {
          case AGroup(userGroup) => Some(userGroup)
          case `N/A` => None
        },
        availableGroups = Set.empty,
        responseHeaders = Vector.empty,
        contextHeaders = Vector.empty,
        kibanaIndex = None,
        indices = Outcome.NotExist,
        repositories = Set.empty,
        snapshots = Set.empty,
        jsonToken = None
      )
    )
}