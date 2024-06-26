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
package tech.beshu.ror.accesscontrol.domain

import cats.Eq
import cats.implicits._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

sealed trait LoggedUser {
  def id: User.Id
}
object LoggedUser {
  final case class DirectlyLoggedUser(id: User.Id) extends LoggedUser
  final case class ImpersonatedUser(id: User.Id, impersonatedBy: User.Id) extends LoggedUser

  implicit val eqLoggedUser: Eq[DirectlyLoggedUser] = Eq.fromUniversalEquals
}

object User {
  final case class Id(value: NonEmptyString)
  object Id {
    implicit def matchable(implicit caseSensitivity: CaseSensitivity): Matchable[Id] =
      Matchable.matchable(_.value.value, caseSensitivity)
    implicit def eq(implicit caseSensitivity: CaseSensitivity): Eq[Id] =
      caseSensitivity match {
        case CaseSensitivity.Enabled => Eq.by(_.value.value)
        case CaseSensitivity.Disabled => Eq.by(_.value.value.toLowerCase)
      }
  }

  final case class UserIdPattern(override val value: Id)
    extends Pattern[Id](value) {

    def containsWildcard: Boolean = value.value.contains("*")
  }
}

sealed abstract class Pattern[T](val value: T)

final case class UserIdPatterns(patterns: UniqueNonEmptyList[User.UserIdPattern])

final case class Group(id: GroupId, name: GroupName)
object Group {
  def from(id: GroupId): Group = Group(id, GroupName.from(id))
}

final case class GroupName(value: NonEmptyString)
object GroupName {
  def from(id: GroupId): GroupName = GroupName(id.value)
}

sealed trait GroupIdLike
object GroupIdLike {
  final case class GroupId(value: NonEmptyString)
    extends GroupIdLike
  object GroupId {
    implicit val eq: Eq[GroupId] = Eq.by(_.value.value)
  }

  final case class GroupIdPattern private(value: NonEmptyString)
    extends GroupIdLike {

    private[GroupIdLike] lazy val matcher = PatternsMatcher.create[GroupIdLike](Set(this))
  }

  object GroupIdPattern {
    implicit val matchable: Matchable[GroupIdPattern] = Matchable.matchable(_.value.value)
    
    def fromNes(value: NonEmptyString): GroupIdPattern = GroupIdPattern(value)
  }

  def from(value: NonEmptyString): GroupIdLike =
    if (value.contains("*")) GroupIdPattern.fromNes(value)
    else GroupId(value)

  implicit val eq: Eq[GroupIdLike] = Eq.by {
    case GroupId(value) => value.value
    case GroupIdPattern(value) => value.value
  }
  implicit val matchable: Matchable[GroupIdLike] = Matchable.matchable{
    case GroupId(value) => value.value
    case GroupIdPattern(value) => value.value
  }

  implicit class GroupsLikeMatcher(val groupIdLike: GroupIdLike) extends AnyVal {
    def matches(group: Group): Boolean = {
      groupIdLike match {
        case groupId@GroupId(_) => groupId === group.id
        case groupId@GroupIdPattern(_) => groupId.matcher.`match`(group.id)
      }
    }
  }
}

final case class PermittedGroupIds(groupIds: UniqueNonEmptyList[_ <: GroupIdLike]) {
  private[PermittedGroupIds] lazy val matcher = PatternsMatcher.create[GroupIdLike](groupIds)
}
object PermittedGroupIds {

  def from(groups: UniqueNonEmptyList[Group]): PermittedGroupIds = {
    PermittedGroupIds(UniqueNonEmptyList.unsafeFromIterable(groups.toList.map(_.id)))
  }

  implicit class PermittedGroupIdsMatcher(val permittedGroupIds: PermittedGroupIds) extends AnyVal {

    def filterOnlyPermitted(groupsToCheck: Iterable[Group]): UniqueList[Group] = {
      val (permitted, _) = permittedGroupIds
        .groupIds.toList.widen[GroupIdLike]
        .foldLeft((Iterable.empty[Group], groupsToCheck)) {
          case ((alreadyPermittedGroups, groupsToCheckLeft), permittedGroupIdLike: GroupIdLike) =>
            val (matched, notMatched) = groupsToCheckLeft.partition(permittedGroupIdLike.matches)
            (alreadyPermittedGroups ++ matched, notMatched)
        }
      UniqueList.fromIterable(permitted)
    }

    def matches(groupId: GroupId): Boolean = {
      permittedGroupIds.matcher.`match`(groupId)
    }
  }
}

final case class ResolvablePermittedGroupIds(permittedGroupIds: UniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]) {
  def resolveGroupIds[B <: BlockContext](blockContext: B): Option[PermittedGroupIds] = {
    UniqueNonEmptyList
      .fromIterable(resolveAll(permittedGroupIds.toNonEmptyList, blockContext))
      .map(PermittedGroupIds.apply)
  }
}

sealed trait GroupsLogic {
  val permittedGroupIds: PermittedGroupIds
}

object GroupsLogic {
  final case class Or(override val permittedGroupIds: PermittedGroupIds) extends GroupsLogic
  final case class And(override val permittedGroupIds: PermittedGroupIds) extends GroupsLogic

  implicit class GroupsLogicExecutor(val groupsLogic: GroupsLogic) extends AnyVal {
    def availableGroupsFrom(userGroups: UniqueNonEmptyList[Group]): Option[UniqueNonEmptyList[Group]] = {
      groupsLogic match {
        case and@GroupsLogic.And(_) => and.availableGroupsFrom(userGroups)
        case or@GroupsLogic.Or(_) => or.availableGroupsFrom(userGroups)
      }
    }
  }

  implicit class GroupsLogicAndExecutor(val groupsLogic: GroupsLogic.And) extends AnyVal {
    def availableGroupsFrom(userGroups: UniqueNonEmptyList[Group]): Option[UniqueNonEmptyList[Group]] = {
      val atLeastPermittedGroupNotMatched = false
      val userGroupsMatchedSoFar = Vector.empty[Group]
      val (isThereNotPermittedGroup, matchedUserGroups) =
        groupsLogic
          .permittedGroupIds
          .groupIds.toList.widen[GroupIdLike]
          .foldLeft((atLeastPermittedGroupNotMatched, userGroupsMatchedSoFar)) {
            case ((false, userGroupsMatchedSoFar), permittedGroup: GroupIdLike) =>
              val matchedUserGroups = userGroups.toList.filter(userGroup => permittedGroup.matches(userGroup))
              matchedUserGroups match {
                case Nil => (true, userGroupsMatchedSoFar)
                case nonEmptyList => (false, userGroupsMatchedSoFar ++ nonEmptyList)
              }
            case (result@(true, _), _) =>
              result
          }
      if (isThereNotPermittedGroup) None
      else UniqueNonEmptyList.fromIterable(matchedUserGroups)
    }
  }

  implicit class GroupsLogicOrExecutor(val groupsLogic: GroupsLogic.Or) extends AnyVal {
    def availableGroupsFrom(userGroups: UniqueNonEmptyList[Group]): Option[UniqueNonEmptyList[Group]] = {
      val someMatchedUserGroups = groupsLogic.permittedGroupIds.filterOnlyPermitted(userGroups)
      UniqueNonEmptyList.fromIterable(someMatchedUserGroups)
    }
  }
}

final case class LocalUsers(users: Set[User.Id], unknownUsers: Boolean)
object LocalUsers {
  def empty: LocalUsers = LocalUsers(Set.empty, unknownUsers = false)
}

