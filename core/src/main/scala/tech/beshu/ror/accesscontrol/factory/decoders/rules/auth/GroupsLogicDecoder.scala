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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.auth

import cats.implicits.toShow
import io.circe.{Decoder, HCursor}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.domain.GroupsLogic
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*

private[auth] object GroupsLogicDecoder {

  def simpleDecoder[T <: Rule](implicit ruleName: RuleName[T]): Decoder[GroupsLogic] = {
    decoder[T]
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE[GroupsLogic] {
        case GroupsLogicDecodingResult.Success(groupsLogic) => Right(groupsLogic)
        case GroupsLogicDecodingResult.GroupsLogicNotDefined(error) => Left(error)
        case GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(error, _) => Left(error)
      }
      .decoder
  }

  def decoder[T <: Rule](implicit ruleName: RuleName[T]): Decoder[GroupsLogicDecodingResult] =
    Decoder
      .instance { c =>
        for {
          groupsAnd <- decodeAsOption[GroupsLogic.And](c, "groups_and", "roles_and")
          groupsOr <- decodeAsOption[GroupsLogic.Or](c, "groups_or", "groups", "roles")
          groupsNotAllOf <- decodeAsOption[GroupsLogic.NotAllOf](c, "groups_not_all_of")
          groupsNotAnyOf <- decodeAsOption[GroupsLogic.NotAnyOf](c, "groups_not_any_of")
        } yield (groupsOr, groupsAnd, groupsNotAllOf, groupsNotAnyOf)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .map[GroupsLogicDecodingResult] {
        case (None, None, None, None) =>
          GroupsLogicDecodingResult.GroupsLogicNotDefined(
            RulesLevelCreationError(Message(errorMsgNoGroupsList(ruleName)))
          )
        case (Some((groupsOr, _)), None, None, None) =>
          GroupsLogicDecodingResult.Success(groupsOr)
        case (None, Some((groupsAnd, _)), None, None) =>
          GroupsLogicDecodingResult.Success(groupsAnd)
        case (None, None, Some((groupsNotAllOf, _)), None) =>
          GroupsLogicDecodingResult.Success(groupsNotAllOf)
        case (None, None, None, Some((groupsNotAnyOf, _))) =>
          GroupsLogicDecodingResult.Success(groupsNotAnyOf)
        case (Some((groupsOr, _)), None, Some((groupsNotAllOf, _)), None) =>
          GroupsLogicDecodingResult.Success(GroupsLogic.CombinedGroupsLogic(groupsOr, groupsNotAllOf))
        case (None, Some((groupsAnd, _)), Some((groupsNotAllOf, _)), None) =>
          GroupsLogicDecodingResult.Success(GroupsLogic.CombinedGroupsLogic(groupsAnd, groupsNotAllOf))
        case (Some((groupsOr, _)), None, None, Some((groupsNotAnyOf, _))) =>
          GroupsLogicDecodingResult.Success(GroupsLogic.CombinedGroupsLogic(groupsOr, groupsNotAnyOf))
        case (None, Some((groupsAnd, _)), None, Some((groupsNotAnyOf, _))) =>
          GroupsLogicDecodingResult.Success(GroupsLogic.CombinedGroupsLogic(groupsAnd, groupsNotAnyOf))
        case (groupsOrOpt, groupsAndOpt, groupsNotAllOfOpt, groupsNotAnyOfOpt) =>
          GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(
            RulesLevelCreationError(Message(errorMsgOnlyOneGroupsList(ruleName))),
            List(groupsOrOpt, groupsAndOpt, groupsNotAllOfOpt, groupsNotAnyOfOpt).flatten.map(_._2),
          )
      }
      .decoder


  private def decodeAsOption[GL <: GroupsLogic : Decoder](c: HCursor, field: String, fields: String*) = {
    val (cursor, key) = c.downFieldsWithKey(field, fields: _*)
    cursor.as[Option[GL]].map(_.map((_, key)))
  }

  private[rules] def errorMsgNoGroupsList[R <: Rule](ruleName: RuleName[R]) = {
    s"${ruleName.show} rule requires to define 'groups_or'/'groups'/'groups_and'/'groups_not_all_of' arrays"
  }

  private[rules] def errorMsgOnlyOneGroupsList[R <: Rule](ruleName: RuleName[R]) = {
    s"${ruleName.show} rule requires to define 'groups_or'/'groups'/'groups_and'/'groups_not_all_of' arrays (but not all)"
  }

  sealed trait GroupsLogicDecodingResult

  object GroupsLogicDecodingResult {
    final case class Success(groupsLogic: GroupsLogic)
      extends GroupsLogicDecodingResult

    final case class GroupsLogicNotDefined(error: RulesLevelCreationError)
      extends GroupsLogicDecodingResult

    final case class MultipleGroupsLogicsDefined(error: RulesLevelCreationError, fields: List[String])
      extends GroupsLogicDecodingResult
  }

}
