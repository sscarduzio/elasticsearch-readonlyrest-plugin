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
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.GroupsLogic.{NegativeGroupsLogic, PositiveGroupsLogic}
import tech.beshu.ror.accesscontrol.domain.{GroupIdLike, GroupsLogic}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.accesscontrol.utils.{SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

private[auth] object GroupsLogicDecoder {

  def a(implicit variableCreator: RuntimeResolvableVariableCreator): SyncDecoder[UniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]] = {
    DecoderHelpers
      .decoderStringLikeOrUniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
  }


  def decoder[T <: Rule](implicit ruleName: RuleName[T]): Decoder[GroupsLogicDecodingResult[GroupsLogic]] = {
    syncGroupsLogicDecoder.decoder
  }

  def simpleDecoder[T <: Rule](implicit ruleName: RuleName[T]): Decoder[GroupsLogic] = {
    syncGroupsLogicDecoder[T]
      .emapE[GroupsLogic] {
        case GroupsLogicDecodingResult.Success(groupsLogic) => Right(groupsLogic)
        case GroupsLogicDecodingResult.GroupsLogicNotDefined(error) => Left(error)
        case GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(error, _) => Left(error)
      }
      .decoder
  }

  private def syncGroupsLogicDecoder[T <: Rule](implicit ruleName: RuleName[T]): SyncDecoder[GroupsLogicDecodingResult[GroupsLogic]] = {
    syncDecoder[
      T,
      GroupsLogic,
      PositiveGroupsLogic,
      NegativeGroupsLogic,
      GroupsLogic.AllOf,
      GroupsLogic.AnyOf,
      GroupsLogic.NotAllOf,
      GroupsLogic.NotAnyOf,
    ](GroupsLogic.Combined.apply)
  }

  private def syncDecoder[
    T <: Rule,
    RULE_REPRESENTATION,
    POSITIVE_RULE_REPRESENTATION <: RULE_REPRESENTATION,
    NEGATIVE_RULE_REPRESENTATION <: RULE_REPRESENTATION,
    ALL_OF_REPRESENTATION <: POSITIVE_RULE_REPRESENTATION : Decoder,
    ANY_OF_REPRESENTATION <: POSITIVE_RULE_REPRESENTATION : Decoder,
    NOT_ALL_OF_REPRESENTATION <: NEGATIVE_RULE_REPRESENTATION : Decoder,
    NOT_ANY_OF_REPRESENTATION <: NEGATIVE_RULE_REPRESENTATION : Decoder,
  ](combinedRuleCreator: (POSITIVE_RULE_REPRESENTATION, NEGATIVE_RULE_REPRESENTATION) => RULE_REPRESENTATION)
   (implicit ruleName: RuleName[T]): SyncDecoder[GroupsLogicDecodingResult[RULE_REPRESENTATION]] = {
    withGroupsSectionDecoder[
      T,
      RULE_REPRESENTATION,
      POSITIVE_RULE_REPRESENTATION,
      NEGATIVE_RULE_REPRESENTATION,
      ALL_OF_REPRESENTATION,
      ANY_OF_REPRESENTATION,
      NOT_ALL_OF_REPRESENTATION,
      NOT_ANY_OF_REPRESENTATION,
    ](combinedRuleCreator).flatMap[GroupsLogicDecodingResult[RULE_REPRESENTATION]] {
      case success: GroupsLogicDecodingResult.Success[RULE_REPRESENTATION] =>
        SyncDecoderCreator.pure(success)
      case error: GroupsLogicDecodingResult.MultipleGroupsLogicsDefined[RULE_REPRESENTATION] =>
        SyncDecoderCreator.pure(error)
      case GroupsLogicDecodingResult.GroupsLogicNotDefined(_) =>
        legacyWithoutGroupsSectionDecoder[
          T,
          RULE_REPRESENTATION,
          POSITIVE_RULE_REPRESENTATION,
          NEGATIVE_RULE_REPRESENTATION,
          ALL_OF_REPRESENTATION,
          ANY_OF_REPRESENTATION,
          NOT_ALL_OF_REPRESENTATION,
          NOT_ANY_OF_REPRESENTATION,
        ](combinedRuleCreator)
    }
  }

  private def withGroupsSectionDecoder[
    T <: Rule,
    RULE_REPRESENTATION,
    POSITIVE_RULE_REPRESENTATION <: RULE_REPRESENTATION,
    NEGATIVE_RULE_REPRESENTATION <: RULE_REPRESENTATION,
    ALL_OF_REPRESENTATION <: POSITIVE_RULE_REPRESENTATION : Decoder,
    ANY_OF_REPRESENTATION <: POSITIVE_RULE_REPRESENTATION : Decoder,
    NOT_ALL_OF_REPRESENTATION <: NEGATIVE_RULE_REPRESENTATION : Decoder,
    NOT_ANY_OF_REPRESENTATION <: NEGATIVE_RULE_REPRESENTATION : Decoder,
  ](combinedRuleCreator: (POSITIVE_RULE_REPRESENTATION, NEGATIVE_RULE_REPRESENTATION) => RULE_REPRESENTATION)
   (implicit ruleName: RuleName[T]): SyncDecoder[GroupsLogicDecodingResult[RULE_REPRESENTATION]] =
    Decoder
      .instance { c =>
        val groupsSection = c.downField("user_belongs_to_groups")
        for {
          groupsAllOf <- decodeAsOption[ALL_OF_REPRESENTATION](groupsSection, "all_of")
          groupsAnyOf <- decodeAsOption[ANY_OF_REPRESENTATION](groupsSection, "any_of")
          groupsNotAllOf <- decodeAsOption[NOT_ALL_OF_REPRESENTATION](groupsSection, "not_all_of")
          groupsNotAnyOf <- decodeAsOption[NOT_ANY_OF_REPRESENTATION](groupsSection, "not_any_of")
        } yield (groupsAnyOf, groupsAllOf, groupsNotAllOf, groupsNotAnyOf)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .map(resultFromLogic(combinedRuleCreator))

  private def legacyWithoutGroupsSectionDecoder[
    T <: Rule,
    RULE_REPRESENTATION,
    POSITIVE_RULE_REPRESENTATION <: RULE_REPRESENTATION,
    NEGATIVE_RULE_REPRESENTATION <: RULE_REPRESENTATION,
    ALL_OF_REPRESENTATION <: POSITIVE_RULE_REPRESENTATION : Decoder,
    ANY_OF_REPRESENTATION <: POSITIVE_RULE_REPRESENTATION : Decoder,
    NOT_ALL_OF_REPRESENTATION <: NEGATIVE_RULE_REPRESENTATION : Decoder,
    NOT_ANY_OF_REPRESENTATION <: NEGATIVE_RULE_REPRESENTATION : Decoder,
  ](combinedRuleCreator: (POSITIVE_RULE_REPRESENTATION, NEGATIVE_RULE_REPRESENTATION) => RULE_REPRESENTATION)
   (implicit ruleName: RuleName[T]): SyncDecoder[GroupsLogicDecodingResult[RULE_REPRESENTATION]] =
    Decoder
      .instance { c =>
        for {
          groupsAllOf <- decodeAsOption[ALL_OF_REPRESENTATION](c, "groups_all_of", "groups_and", "roles_and")
          groupsAnyOf <- decodeAsOption[ANY_OF_REPRESENTATION](c, "groups_any_of", "groups_or", "groups", "roles")
          groupsNotAllOf <- decodeAsOption[NOT_ALL_OF_REPRESENTATION](c, "groups_not_all_of")
          groupsNotAnyOf <- decodeAsOption[NOT_ANY_OF_REPRESENTATION](c, "groups_not_any_of")
        } yield (groupsAllOf, groupsAnyOf, groupsNotAllOf, groupsNotAnyOf)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .map(resultFromLogic(combinedRuleCreator))

  private def resultFromLogic[
    T <: Rule,
    RULE_REPRESENTATION,
    POSITIVE_RULE_REPRESENTATION <: RULE_REPRESENTATION,
    NEGATIVE_RULE_REPRESENTATION <: RULE_REPRESENTATION,
    ALL_OF_REPRESENTATION <: POSITIVE_RULE_REPRESENTATION : Decoder,
    ANY_OF_REPRESENTATION <: POSITIVE_RULE_REPRESENTATION : Decoder,
    NOT_ALL_OF_REPRESENTATION <: NEGATIVE_RULE_REPRESENTATION : Decoder,
    NOT_ANY_OF_REPRESENTATION <: NEGATIVE_RULE_REPRESENTATION : Decoder,
  ](combinedRuleCreator: (POSITIVE_RULE_REPRESENTATION, NEGATIVE_RULE_REPRESENTATION) => RULE_REPRESENTATION)
   (logic: (Option[(ALL_OF_REPRESENTATION, String)], Option[(ANY_OF_REPRESENTATION, String)], Option[(NOT_ALL_OF_REPRESENTATION, String)], Option[(NOT_ANY_OF_REPRESENTATION, String)]))
   (implicit ruleName: RuleName[T]): GroupsLogicDecodingResult[RULE_REPRESENTATION] =
    logic match {
      case (None, None, None, None) =>
        GroupsLogicDecodingResult.GroupsLogicNotDefined(
          RulesLevelCreationError(Message(errorMsgNoGroupsList(ruleName)))
        )
      case (Some((groupsAllOf, _)), None, None, None) =>
        GroupsLogicDecodingResult.Success(groupsAllOf)
      case (None, Some((groupsAnyOf, _)), None, None) =>
        GroupsLogicDecodingResult.Success(groupsAnyOf)
      case (None, None, Some((groupsNotAllOf, _)), None) =>
        GroupsLogicDecodingResult.Success(groupsNotAllOf)
      case (None, None, None, Some((groupsNotAnyOf, _))) =>
        GroupsLogicDecodingResult.Success(groupsNotAnyOf)
      case (Some((groupsAllOf, _)), None, Some((groupsNotAllOf, _)), None) =>
        GroupsLogicDecodingResult.Success(combinedRuleCreator(groupsAllOf, groupsNotAllOf))
      case (None, Some((groupsAnyOf, _)), Some((groupsNotAllOf, _)), None) =>
        GroupsLogicDecodingResult.Success(combinedRuleCreator(groupsAnyOf, groupsNotAllOf))
      case (Some((groupsAllOf, _)), None, None, Some((groupsNotAnyOf, _))) =>
        GroupsLogicDecodingResult.Success(combinedRuleCreator(groupsAllOf, groupsNotAnyOf))
      case (None, Some((groupsAnyOf, _)), None, Some((groupsNotAnyOf, _))) =>
        GroupsLogicDecodingResult.Success(combinedRuleCreator(groupsAnyOf, groupsNotAnyOf))
      case (groupsAnyOfOpt, groupsAllOfOpt, groupsNotAllOfOpt, groupsNotAnyOfOpt) =>
        GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(
          RulesLevelCreationError(Message(errorMsgOnlyOneGroupsList(ruleName))),
          List(groupsAnyOfOpt, groupsAllOfOpt, groupsNotAllOfOpt, groupsNotAnyOfOpt).flatten.map(_._2),
        )
    }

  private def decodeAsOption[REPRESENTATION: Decoder](c: ACursor, field: String, fields: String*) = {
    val (cursor, key) = c.downFieldsWithKey(field, fields: _*)
    cursor.as[Option[REPRESENTATION]].map(_.map((_, key)))
  }

  private[rules] def errorMsgNoGroupsList[R <: Rule](ruleName: RuleName[R]) = {
    s"${ruleName.show} rule requires to define 'groups_or'/'groups'/'groups_and'/'groups_not_all_of' arrays"
  }

  private[rules] def errorMsgOnlyOneGroupsList[R <: Rule](ruleName: RuleName[R]) = {
    s"${ruleName.show} rule requires to define 'groups_or'/'groups'/'groups_and'/'groups_not_all_of' arrays (but not all)"
  }

  sealed trait GroupsLogicDecodingResult[RULE_REPRESENTATION]

  object GroupsLogicDecodingResult {
    final case class Success[RULE_REPRESENTATION](groupsLogic: RULE_REPRESENTATION)
      extends GroupsLogicDecodingResult[RULE_REPRESENTATION]

    final case class GroupsLogicNotDefined[RULE_REPRESENTATION](error: RulesLevelCreationError)
      extends GroupsLogicDecodingResult[RULE_REPRESENTATION]

    final case class MultipleGroupsLogicsDefined[RULE_REPRESENTATION](error: RulesLevelCreationError, fields: List[String])
      extends GroupsLogicDecodingResult[RULE_REPRESENTATION]
  }

}
