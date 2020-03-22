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

import cats.{Eval, Foldable, Functor}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{IndexRelated, Outcome}
import tech.beshu.ror.accesscontrol.blocks.RequestContextInitiatedBlockContext.BlockContextData
import tech.beshu.ror.accesscontrol.domain.{Operation, _}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

trait BlockContext[T <: Operation] {

  def loggedUser: Option[LoggedUser]
  def withLoggedUser(user: LoggedUser): BlockContext[T]

  def jwt: Option[JwtTokenPayload]
  def withJwt(token: JwtTokenPayload): BlockContext[T]

  def availableGroups: UniqueList[Group]
  def currentGroup: Option[Group]
  def withAddedAvailableGroups(groups: UniqueNonEmptyList[Group]): BlockContext[T]

  def responseHeaders: Set[Header]
  def withAddedResponseHeader(header: Header): BlockContext[T]

  def contextHeaders: Set[Header]
  def withAddedContextHeader(header: Header): BlockContext[T]

  def indices: Outcome[Set[IndexName]]
  def withIndices(indices: Set[IndexName]): BlockContext[T]

  def filteredIndices: Outcome[Set[IndexRelated]]
  def withFilteredIndices(indexRelated: Set[IndexRelated]): BlockContext[T]

  def repositories: Outcome[Set[IndexName]]
  def withRepositories(indices: Set[IndexName]): BlockContext[T]

  def snapshots: Outcome[Set[IndexName]]
  def withSnapshots(indices: Set[IndexName]): BlockContext[T]

  def hiddenKibanaApps: Set[KibanaApp]
  def withHiddenKibanaApps(apps: Set[KibanaApp]): BlockContext[T]

  def userOrigin: Option[UserOrigin]
  def withUserOrigin(origin: UserOrigin): BlockContext[T]

  def kibanaAccess: Option[KibanaAccess]
  def withKibanaAccess(access: KibanaAccess): BlockContext[T]

  def kibanaIndex: Option[IndexName]
  def withKibanaIndex(index: IndexName): BlockContext[T]

  def kibanaTemplateIndex: Option[IndexName]
  def withKibanaTemplateIndex(index: IndexName): BlockContext[T]

  def modifyOperation(modification: OperationModification[T]): BlockContext[T]
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

    implicit val functor: Functor[Outcome] = new Functor[Outcome] {
      override def map[A, B](fa: Outcome[A])(f: A => B): Outcome[B] = fa match {
        case Exist(value) => Exist(f(value))
        case NotExist => NotExist
      }
    }
    implicit val foldable: Foldable[Outcome] = new Foldable[Outcome] {
      override def foldLeft[A, B](fa: Outcome[A], b: B)(f: (B, A) => B): B = {
        fa match {
          case Exist(a) => f(b, a)
          case NotExist => b
        }
      }

      override def foldRight[A, B](fa: Outcome[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = {
        fa match {
          case Exist(a) => f(a, lb)
          case NotExist => lb
        }
      }
    }
  }

  sealed trait IndexRelated
  object IndexRelated {
    final case class Itself(index: IndexName) extends IndexRelated
    final case class Template(name: Template, indices: Set[IndexName]) extends IndexRelated
  }
}

class NoOpBlockContext[T <: Operation] extends BlockContext[T] {
  override val loggedUser: Option[LoggedUser] = None
  override def withLoggedUser(user: LoggedUser): BlockContext[T] = this

  override val jwt: Option[JwtTokenPayload] = None
  override def withJwt(token: JwtTokenPayload): BlockContext[T] = this

  override val availableGroups: UniqueList[Group] = UniqueList.empty
  override def withAddedAvailableGroups(groups: UniqueNonEmptyList[Group]): BlockContext[T]= this
  override val currentGroup: Option[Group] = None

  override val responseHeaders: Set[Header] = Set.empty
  override def withAddedResponseHeader(header: Header): BlockContext[T]= this

  override val hiddenKibanaApps: Set[KibanaApp] = Set.empty
  override def withHiddenKibanaApps(apps: Set[KibanaApp]): BlockContext[T]= this

  override val kibanaAccess: Option[KibanaAccess] = None
  override def withKibanaAccess(access: KibanaAccess): BlockContext[T]= this

  override val contextHeaders: Set[Header] = Set.empty
  override def withAddedContextHeader(header: Header): BlockContext[T]= this

  override val kibanaIndex: Option[IndexName] = None
  override def withKibanaIndex(index: IndexName): BlockContext[T]= this

  override val kibanaTemplateIndex: Option[IndexName] = None
  override def withKibanaTemplateIndex(index: IndexName): BlockContext[T]= this

  override val userOrigin: Option[UserOrigin] = None
  override def withUserOrigin(origin: UserOrigin): BlockContext[T]= this

  override val indices: Outcome[Set[IndexName]] = Outcome.NotExist
  override def withIndices(indices: Set[IndexName]): BlockContext[T]= this

  override val repositories: Outcome[Set[IndexName]] = Outcome.NotExist
  override def withRepositories(indices: Set[IndexName]): BlockContext[T]= this

  override val snapshots: Outcome[Set[IndexName]] = Outcome.NotExist
  override def withSnapshots(indices: Set[IndexName]): BlockContext[T]= this

  override def filteredIndices: Outcome[Set[IndexRelated]] = ???
  override def withFilteredIndices(indexRelated: Set[IndexRelated]): BlockContext[T]= ???

  override def modifyOperation(modification: OperationModification[T]): BlockContext[T]= ???
}

class RequestContextInitiatedBlockContext[T <: Operation] private(val data: BlockContextData)
  extends BlockContext[T] {

  override def loggedUser: Option[LoggedUser] = data.loggedUser

  override def withLoggedUser(user: LoggedUser): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(loggedUser = Some(user)))

  override def availableGroups: UniqueList[Group] = data.availableGroups

  override def withAddedAvailableGroups(groups: UniqueNonEmptyList[Group]): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(availableGroups = UniqueList.fromSortedSet(data.availableGroups ++ groups.toUniqueList)))

  override def currentGroup: Option[Group] = data.initialCurrentGroup match {
    case Some(initialGroup) => Some(initialGroup)
    case None => availableGroups.headOption
  }

  override def responseHeaders: Set[Header] = data.responseHeaders.toSet

  override def withAddedResponseHeader(header: Header): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(responseHeaders = data.responseHeaders :+ header))

  override def hiddenKibanaApps: Set[KibanaApp] = data.hiddenKibanaApps

  override def withHiddenKibanaApps(apps: Set[KibanaApp]): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(hiddenKibanaApps = apps))

  override def kibanaAccess: Option[KibanaAccess] = data.kibanaAccess

  override def withKibanaAccess(access: KibanaAccess): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(kibanaAccess = Some(access)))

  override def userOrigin: Option[UserOrigin] = data.userOrigin

  override def withUserOrigin(origin: UserOrigin): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(userOrigin = Some(origin)))

  override def contextHeaders: Set[Header] = data.contextHeaders.toSet

  override def withAddedContextHeader(header: Header): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(contextHeaders = data.contextHeaders :+ header))

  override def kibanaIndex: Option[IndexName] = data.kibanaIndex

  override def withKibanaIndex(index: IndexName): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(kibanaIndex = Some(index)))

  override def kibanaTemplateIndex: Option[IndexName] = data.kibanaTemplateIndex

  override def withKibanaTemplateIndex(index: IndexName): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(kibanaTemplateIndex = Some(index)))

  override def indices: Outcome[Set[IndexName]] = data.indices

  override def withIndices(indices: Set[IndexName]): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(indices = Outcome.Exist(indices)))

  override def repositories: Outcome[Set[IndexName]] = data.repositories

  override def withRepositories(repositories: Set[IndexName]): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(repositories = Outcome.Exist(repositories)))

  override def snapshots: Outcome[Set[IndexName]] = data.snapshots

  override def withSnapshots(snapshots: Set[IndexName]): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(snapshots = Outcome.Exist(snapshots)))

  override def jwt: Option[JwtTokenPayload] = data.jsonToken

  override def withJwt(token: JwtTokenPayload): BlockContext[T] =
    new RequestContextInitiatedBlockContext[T](data.copy(jsonToken = Some(token)))

  override def filteredIndices: Outcome[Set[IndexRelated]] = ???

  override def withFilteredIndices(indexRelated: Set[IndexRelated]): BlockContext[T] = ???

  override def modifyOperation(modification: OperationModification[T]): BlockContext[T] = ???
}

object RequestContextInitiatedBlockContext {

  final case class BlockContextData(loggedUser: Option[LoggedUser],
                                    initialCurrentGroup: Option[Group],
                                    availableGroups: UniqueList[Group],
                                    hiddenKibanaApps: Set[KibanaApp],
                                    responseHeaders: Vector[Header],
                                    contextHeaders: Vector[Header],
                                    kibanaIndex: Option[IndexName],
                                    kibanaTemplateIndex: Option[IndexName],
                                    kibanaAccess: Option[KibanaAccess],
                                    userOrigin: Option[UserOrigin],
                                    indices: Outcome[Set[IndexName]],
                                    repositories: Outcome[Set[IndexName]],
                                    snapshots: Outcome[Set[IndexName]],
                                    jsonToken: Option[JwtTokenPayload])

  def fromRequestContext[T <: Operation](requestContext: RequestContext[T]): RequestContextInitiatedBlockContext[T] =
    new RequestContextInitiatedBlockContext(
      BlockContextData(
        loggedUser = None,
        initialCurrentGroup = requestContext.currentGroup.toOption,
        availableGroups = UniqueList.empty,
        hiddenKibanaApps = Set.empty,
        responseHeaders = Vector.empty,
        contextHeaders = Vector.empty,
        kibanaIndex = None,
        kibanaTemplateIndex = None,
        kibanaAccess = None,
        userOrigin = None,
        indices = Outcome.NotExist,
        repositories = Outcome.NotExist,
        snapshots = Outcome.NotExist,
        jsonToken = None
      )
    )
}