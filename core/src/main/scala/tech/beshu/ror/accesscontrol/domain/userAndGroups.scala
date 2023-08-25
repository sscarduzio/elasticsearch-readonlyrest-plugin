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

import cats.implicits._
import cats.Eq
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.matchers.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.CaseMappingEquality
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName

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
    type UserIdCaseMappingEquality = CaseMappingEquality[User.Id]
  }

  final case class UserIdPattern(override val value: NonEmptyString)
    extends Pattern[Id](value) {

    def containsWildcard: Boolean = value.value.contains("*")
  }
}

abstract class Pattern[T](val value: NonEmptyString)

final case class UserIdPatterns(patterns: UniqueNonEmptyList[User.UserIdPattern])

sealed trait GroupLike
object GroupLike {
  final case class GroupName(value: NonEmptyString)
    extends GroupLike
  object GroupName {
    implicit val eq: Eq[GroupName] = Eq.by(_.value.value)
  }

  final case class GroupNamePattern private(value: NonEmptyString)
    extends GroupLike {

    private[GroupLike] lazy val matcher = MatcherWithWildcardsScalaAdapter.create[GroupLike](Set(this))
  }
  object GroupNamePattern {
    implicit val caseMappingEquality: CaseMappingEquality[GroupNamePattern] =
      CaseMappingEquality.instance(_.value.value, identity)
  }

  def from(value: NonEmptyString): GroupLike =
    if (value.contains("*")) GroupNamePattern(value)
    else GroupName(value)

  implicit val eq: Eq[GroupLike] = Eq.by {
    case GroupName(value) => value.value
    case GroupNamePattern(value) => value.value
  }
  implicit val caseMappingEquality: CaseMappingEquality[GroupLike] =
    CaseMappingEquality.instance(
      {
        case GroupName(value) => value.value
        case GroupNamePattern(value) => value.value
      },
      identity
    )

  implicit class GroupsLikeMatcher(val groupLike: GroupLike) extends AnyVal {
    def matches(groupName: GroupName): Boolean = {
      groupLike match {
        case group@GroupName(_) => group === groupName
        case group@GroupNamePattern(_) => group.matcher.`match`(groupName)
      }
    }
  }
}

final case class PermittedGroups(groups: UniqueNonEmptyList[_ <: GroupLike]) {
  private[PermittedGroups] lazy val matcher = MatcherWithWildcardsScalaAdapter.create[GroupLike](groups)
}
object PermittedGroups {

  implicit class PermittedGroupsMatcher(val permittedGroups: PermittedGroups) extends AnyVal {

    def filterOnlyPermitted(groupsToCheck: Iterable[GroupName]): UniqueList[GroupName] = {
      val (permitted, _) = permittedGroups
        .groups.toList.widen[GroupLike]
        .foldLeft((Iterable.empty[GroupName], groupsToCheck)) {
          case ((alreadyPermittedGroups, groupsToCheckLeft), permittedGroupLike: GroupLike) =>
            val (matched, notMatched) = groupsToCheckLeft.partition(permittedGroupLike.matches)
            (alreadyPermittedGroups ++ matched, notMatched)
        }
      UniqueList.fromIterable(permitted)
    }

    def matches(groupName: GroupName): Boolean = {
      permittedGroups.matcher.`match`(groupName)
    }
  }
}

final case class ResolvablePermittedGroups(permittedGroups: UniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupLike]]) {
  def resolveGroups[B <: BlockContext](blockContext: B): Option[PermittedGroups] = {
    UniqueNonEmptyList
      .fromIterable(resolveAll(permittedGroups.toNonEmptyList, blockContext))
      .map(PermittedGroups.apply)
  }
}

sealed trait GroupsLogic {
  val permittedGroups: PermittedGroups
}

object GroupsLogic {
  final case class Or(override val permittedGroups: PermittedGroups) extends GroupsLogic
  final case class And(override val permittedGroups: PermittedGroups) extends GroupsLogic

  implicit class GroupsLogicExecutor(val groupsLogic: GroupsLogic) extends AnyVal {
    def availableGroupsFrom(userGroups: UniqueNonEmptyList[GroupName]): Option[UniqueNonEmptyList[GroupName]] = {
      groupsLogic match {
        case and@GroupsLogic.And(_) => and.availableGroupsFrom(userGroups)
        case or@GroupsLogic.Or(_) => or.availableGroupsFrom(userGroups)
      }
    }
  }

  implicit class GroupsLogicAndExecutor(val groupsLogic: GroupsLogic.And) extends AnyVal {
    def availableGroupsFrom(userGroups: UniqueNonEmptyList[GroupName]): Option[UniqueNonEmptyList[GroupName]] = {
      val atLeastPermittedGroupNotMatched = false
      val userGroupsMatchedSoFar = Vector.empty[GroupName]
      val (isThereNotPermittedGroup, matchedUserGroups) =
        groupsLogic
          .permittedGroups
          .groups.toList.widen[GroupLike]
          .foldLeft((atLeastPermittedGroupNotMatched, userGroupsMatchedSoFar)) {
            case ((false, userGroupsMatchedSoFar), permittedGroup: GroupLike) =>
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
    def availableGroupsFrom(userGroups: UniqueNonEmptyList[GroupName]): Option[UniqueNonEmptyList[GroupName]] = {
      val someMatchedUserGroups = groupsLogic.permittedGroups.filterOnlyPermitted(userGroups)
      UniqueNonEmptyList.fromIterable(someMatchedUserGroups)
    }
  }
}

final case class LocalUsers(users: Set[User.Id], unknownUsers: Boolean)
object LocalUsers {
  def empty: LocalUsers = LocalUsers(Set.empty, unknownUsers = false)
}

