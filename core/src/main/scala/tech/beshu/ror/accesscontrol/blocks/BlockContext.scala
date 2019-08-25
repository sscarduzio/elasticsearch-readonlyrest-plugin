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
package tech.beshu.ror.accesscontrol.blocks

import cats.data.NonEmptySet
import cats.implicits._
import tech.beshu.ror.accesscontrol.blocks.BlockContext.Outcome
import tech.beshu.ror.accesscontrol.blocks.RequestContextInitiatedBlockContext.BlockContextData
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._

import scala.collection.SortedSet

trait BlockContext {

  def loggedUser: Option[LoggedUser]
  def withLoggedUser(user: LoggedUser): BlockContext

  def jwt: Option[JwtTokenPayload]
  def withJwt(token: JwtTokenPayload): BlockContext

  def availableGroups: SortedSet[Group]
  def currentGroup: Option[Group]
  def withAddedAvailableGroups(groups: NonEmptySet[Group]): BlockContext

  def responseHeaders: Set[Header]
  def withAddedResponseHeader(header: Header): BlockContext

  def contextHeaders: Set[Header]
  def withAddedContextHeader(header: Header): BlockContext

  def indices: Outcome[Set[IndexName]]
  def withIndices(indices: Set[IndexName]): BlockContext

  def repositories: Outcome[Set[IndexName]]
  def withRepositories(indices: Set[IndexName]): BlockContext

  def snapshots: Outcome[Set[IndexName]]
  def withSnapshots(indices: Set[IndexName]): BlockContext

  def hiddenKibanaApps: Set[KibanaApp]
  def withHiddenKibanaApps(apps: Set[KibanaApp]): BlockContext

  def userOrigin: Option[UserOrigin]
  def withUserOrigin(origin: UserOrigin): BlockContext

  def kibanaAccess: Option[KibanaAccess]
  def withKibanaAccess(access: KibanaAccess): BlockContext

  def kibanaIndex: Option[IndexName]
  def withKibanaIndex(index: IndexName): BlockContext

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

  override val jwt: Option[JwtTokenPayload] = None
  override def withJwt(token: JwtTokenPayload): BlockContext = this

  override val availableGroups: SortedSet[Group] = SortedSet.empty
  override def withAddedAvailableGroups(groups: NonEmptySet[Group]): BlockContext = this
  override val currentGroup: Option[Group] = None

  override val responseHeaders: Set[Header] = Set.empty
  override def withAddedResponseHeader(header: Header): BlockContext = this

  override val hiddenKibanaApps: Set[KibanaApp] = Set.empty
  override def withHiddenKibanaApps(apps: Set[KibanaApp]): BlockContext = this

  override val kibanaAccess: Option[KibanaAccess] = None
  override def withKibanaAccess(access: KibanaAccess): BlockContext = this

  override val contextHeaders: Set[Header] = Set.empty
  override def withAddedContextHeader(header: Header): BlockContext = this

  override val kibanaIndex: Option[IndexName] = None
  override def withKibanaIndex(index: IndexName): BlockContext = this

  override val userOrigin: Option[UserOrigin] = None
  override def withUserOrigin(origin: UserOrigin): BlockContext = this

  override val indices: Outcome[Set[IndexName]] = Outcome.NotExist
  override def withIndices(indices: Set[IndexName]): BlockContext = this

  override val repositories: Outcome[Set[IndexName]] = Outcome.NotExist
  override def withRepositories(indices: Set[IndexName]): BlockContext = this

  override val snapshots: Outcome[Set[IndexName]] = Outcome.NotExist
  override def withSnapshots(indices: Set[IndexName]): BlockContext = this
}

class RequestContextInitiatedBlockContext private(val data: BlockContextData)
  extends BlockContext {

  override def loggedUser: Option[LoggedUser] = data.loggedUser

  override def withLoggedUser(user: LoggedUser): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(loggedUser = Some(user)))

  override def availableGroups: SortedSet[Group] = SortedSet.empty[Group] ++ data.availableGroups

  override def withAddedAvailableGroups(groups: NonEmptySet[Group]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(availableGroups = data.availableGroups ++ groups.toSortedSet))

  override def currentGroup: Option[Group] = data.initialCurrentGroup match {
    case Some(initialGroup) => Some(initialGroup)
    case None => availableGroups.headOption
  }

  override def responseHeaders: Set[Header] = data.responseHeaders.toSet

  override def withAddedResponseHeader(header: Header): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(responseHeaders = data.responseHeaders :+ header))

  override def hiddenKibanaApps: Set[KibanaApp] = data.hiddenKibanaApps

  override def withHiddenKibanaApps(apps: Set[KibanaApp]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(hiddenKibanaApps = apps))

  override def kibanaAccess: Option[KibanaAccess] = data.kibanaAccess

  override def withKibanaAccess(access: KibanaAccess): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(kibanaAccess = Some(access)))

  override def userOrigin: Option[UserOrigin] = data.userOrigin

  override def withUserOrigin(origin: UserOrigin): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(userOrigin = Some(origin)))

  override def contextHeaders: Set[Header] = data.contextHeaders.toSet

  override def withAddedContextHeader(header: Header): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(contextHeaders = data.contextHeaders :+ header))

  override def kibanaIndex: Option[IndexName] = data.kibanaIndex

  override def withKibanaIndex(index: IndexName): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(kibanaIndex = Some(index)))

  override def indices: Outcome[Set[IndexName]] = data.indices

  override def withIndices(indices: Set[IndexName]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(indices = Outcome.Exist(indices)))

  override def repositories: Outcome[Set[IndexName]] = data.repositories

  override def withRepositories(repositories: Set[IndexName]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(repositories = Outcome.Exist(repositories)))

  override def snapshots: Outcome[Set[IndexName]] = data.snapshots

  override def withSnapshots(snapshots: Set[IndexName]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(snapshots = Outcome.Exist(snapshots)))

  override def jwt: Option[JwtTokenPayload] = data.jsonToken

  override def withJwt(token: JwtTokenPayload): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(jsonToken = Some(token)))
}

object RequestContextInitiatedBlockContext {

  final case class BlockContextData(loggedUser: Option[LoggedUser],
                                    initialCurrentGroup: Option[Group],
                                    availableGroups: Set[Group],
                                    hiddenKibanaApps: Set[KibanaApp],
                                    responseHeaders: Vector[Header],
                                    contextHeaders: Vector[Header],
                                    kibanaIndex: Option[IndexName],
                                    kibanaAccess: Option[KibanaAccess],
                                    userOrigin: Option[UserOrigin],
                                    indices: Outcome[Set[IndexName]],
                                    repositories: Outcome[Set[IndexName]],
                                    snapshots: Outcome[Set[IndexName]],
                                    jsonToken: Option[JwtTokenPayload])

  def fromRequestContext(requestContext: RequestContext): RequestContextInitiatedBlockContext =
    new RequestContextInitiatedBlockContext(
      BlockContextData(
        loggedUser = None,
        initialCurrentGroup = requestContext.currentGroup.toOption,
        availableGroups = Set.empty,
        hiddenKibanaApps = Set.empty,
        responseHeaders = Vector.empty,
        contextHeaders = Vector.empty,
        kibanaIndex = None,
        kibanaAccess = None,
        userOrigin = None,
        indices = Outcome.NotExist,
        repositories = Outcome.NotExist,
        snapshots = Outcome.NotExist,
        jsonToken = None
      )
    )
}