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
import io.circe.{ACursor, Decoder}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.domain.GroupsLogic
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.accesscontrol.utils.{SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.implicits.*

private[auth] object GroupsLogicDecoder {

  def decoder[T <: Rule](implicit ruleName: RuleName[T]): Decoder[GroupsLogicDecodingResult] = {
    syncDecoder.decoder
  }

  def simpleDecoder[T <: Rule](implicit ruleName: RuleName[T]): Decoder[GroupsLogic] = {
    syncDecoder[T]
      .emapE[GroupsLogic] {
        case GroupsLogicDecodingResult.Success(groupsLogic) => Right(groupsLogic)
        case GroupsLogicDecodingResult.GroupsLogicNotDefined(error) => Left(error)
        case GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(error, _) => Left(error)
      }
      .decoder
  }

  private def syncDecoder[T <: Rule](implicit ruleName: RuleName[T]): SyncDecoder[GroupsLogicDecodingResult] = {
    withGroupsSectionDecoder[T]
      .flatMap[GroupsLogicDecodingResult] {
        case success@GroupsLogicDecodingResult.Success(_) =>
          SyncDecoderCreator.pure(success)
        case error@GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(_, _) =>
          SyncDecoderCreator.pure(error)
        case GroupsLogicDecodingResult.GroupsLogicNotDefined(_) =>
          legacyWithoutGroupsSectionDecoder
      }
  }

  private def withGroupsSectionDecoder[T <: Rule](implicit ruleName: RuleName[T]): SyncDecoder[GroupsLogicDecodingResult] =
    Decoder
      .instance { c =>
        val groupsSection = c.downField("user_belongs_to_groups")
        for {
          groupsAllOf <- decodeAsOption[GroupsLogic.AllOf](groupsSection, "all_of")
          groupsAnyOf <- decodeAsOption[GroupsLogic.AnyOf](groupsSection, "any_of")
          groupsNotAllOf <- decodeAsOption[GroupsLogic.NotAllOf](groupsSection, "not_all_of")
          groupsNotAnyOf <- decodeAsOption[GroupsLogic.NotAnyOf](groupsSection, "not_any_of")
        } yield (groupsAnyOf, groupsAllOf, groupsNotAllOf, groupsNotAnyOf)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .map(resultFromLogic)

  private def legacyWithoutGroupsSectionDecoder[T <: Rule](implicit ruleName: RuleName[T]): SyncDecoder[GroupsLogicDecodingResult] =
    Decoder
      .instance { c =>
        for {
          groupsAllOf <- decodeAsOption[GroupsLogic.AllOf](c, "groups_and", "roles_and")
          groupsAnyOf <- decodeAsOption[GroupsLogic.AnyOf](c, "groups_or", "groups", "roles")
          groupsNotAllOf <- decodeAsOption[GroupsLogic.NotAllOf](c, "groups_not_all_of")
          groupsNotAnyOf <- decodeAsOption[GroupsLogic.NotAnyOf](c, "groups_not_any_of")
        } yield (groupsAnyOf, groupsAllOf, groupsNotAllOf, groupsNotAnyOf)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .map(resultFromLogic)

  private def resultFromLogic[T <: Rule](logic: (Option[(GroupsLogic.AnyOf, String)], Option[(GroupsLogic.AllOf, String)], Option[(GroupsLogic.NotAllOf, String)], Option[(GroupsLogic.NotAnyOf, String)]))
                                        (implicit ruleName: RuleName[T]): GroupsLogicDecodingResult =
    logic match {
      case (None, None, None, None) =>
        GroupsLogicDecodingResult.GroupsLogicNotDefined(
          RulesLevelCreationError(Message(errorMsgNoGroupsList(ruleName)))
        )
      case (Some((groupsAnyOf, _)), None, None, None) =>
        GroupsLogicDecodingResult.Success(groupsAnyOf)
      case (None, Some((groupsAllOf, _)), None, None) =>
        GroupsLogicDecodingResult.Success(groupsAllOf)
      case (None, None, Some((groupsNotAllOf, _)), None) =>
        GroupsLogicDecodingResult.Success(groupsNotAllOf)
      case (None, None, None, Some((groupsNotAnyOf, _))) =>
        GroupsLogicDecodingResult.Success(groupsNotAnyOf)
      case (Some((groupsAnyOf, _)), None, Some((groupsNotAllOf, _)), None) =>
        GroupsLogicDecodingResult.Success(GroupsLogic.Combined(groupsAnyOf, groupsNotAllOf))
      case (None, Some((groupsAllOf, _)), Some((groupsNotAllOf, _)), None) =>
        GroupsLogicDecodingResult.Success(GroupsLogic.Combined(groupsAllOf, groupsNotAllOf))
      case (Some((groupsAnyOf, _)), None, None, Some((groupsNotAnyOf, _))) =>
        GroupsLogicDecodingResult.Success(GroupsLogic.Combined(groupsAnyOf, groupsNotAnyOf))
      case (None, Some((groupsAllOf, _)), None, Some((groupsNotAnyOf, _))) =>
        GroupsLogicDecodingResult.Success(GroupsLogic.Combined(groupsAllOf, groupsNotAnyOf))
      case (groupsAnyOfOpt, groupsAllOfOpt, groupsNotAllOfOpt, groupsNotAnyOfOpt) =>
        GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(
          RulesLevelCreationError(Message(errorMsgOnlyOneGroupsList(ruleName))),
          List(groupsAnyOfOpt, groupsAllOfOpt, groupsNotAllOfOpt, groupsNotAnyOfOpt).flatten.map(_._2),
        )
    }

  private def decodeAsOption[GL <: GroupsLogic : Decoder](c: ACursor, field: String, fields: String*) = {
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
