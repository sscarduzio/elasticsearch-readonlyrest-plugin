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
import tech.beshu.ror.accesscontrol.blocks.rules.auth.*
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.GroupsLogicRepresentationDecoder.GroupsLogicDecodingResult
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.accesscontrol.utils.{SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.implicits.*

private[auth] class GroupsLogicRepresentationDecoder[
  RULE_REPRESENTATION,
  POSITIVE_RULE_REPRESENTATION <: RULE_REPRESENTATION,
  NEGATIVE_RULE_REPRESENTATION <: RULE_REPRESENTATION,
  ALL_OF_REPRESENTATION <: POSITIVE_RULE_REPRESENTATION : Decoder,
  ANY_OF_REPRESENTATION <: POSITIVE_RULE_REPRESENTATION : Decoder,
  NOT_ALL_OF_REPRESENTATION <: NEGATIVE_RULE_REPRESENTATION : Decoder,
  NOT_ANY_OF_REPRESENTATION <: NEGATIVE_RULE_REPRESENTATION : Decoder,
](combinedRuleCreator: (POSITIVE_RULE_REPRESENTATION, NEGATIVE_RULE_REPRESENTATION) => RULE_REPRESENTATION) {

  def decoder[T <: Rule](implicit ruleName: RuleName[T]): Decoder[GroupsLogicDecodingResult[RULE_REPRESENTATION]] = {
    syncDecoder.decoder
  }

  def simpleDecoder[T <: Rule](implicit ruleName: RuleName[T]): Decoder[RULE_REPRESENTATION] = {
    syncDecoder[T]
      .emapE[RULE_REPRESENTATION] {
        case GroupsLogicDecodingResult.Success(groupsLogic) => Right(groupsLogic)
        case GroupsLogicDecodingResult.GroupsLogicNotDefined(error) => Left(error)
        case GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(error, _) => Left(error)
      }
      .decoder
  }

  def syncDecoder[T <: Rule](implicit ruleName: RuleName[T]): SyncDecoder[GroupsLogicDecodingResult[RULE_REPRESENTATION]] = {
    withGroupsSectionDecoder[T].flatMap[GroupsLogicDecodingResult[RULE_REPRESENTATION]] {
      case success: GroupsLogicDecodingResult.Success[RULE_REPRESENTATION] =>
        SyncDecoderCreator.pure(success)
      case error: GroupsLogicDecodingResult.MultipleGroupsLogicsDefined[RULE_REPRESENTATION] =>
        SyncDecoderCreator.pure(error)
      case GroupsLogicDecodingResult.GroupsLogicNotDefined(a) =>
        legacyWithoutGroupsSectionDecoder[T]
    }
  }

  private def withGroupsSectionDecoder[T <: Rule](implicit ruleName: RuleName[T]): SyncDecoder[GroupsLogicDecodingResult[RULE_REPRESENTATION]] =
    Decoder
      .instance { c =>
        val groupsSection = c.downField(GroupsRule.GroupsSubruleSectionName.name.value)
        for {
          groupsAllOf <- decodeAsOption[ALL_OF_REPRESENTATION](groupsSection)(AllOfGroupsRule.NameInsideGroupsSubruleSection.name.value)
          groupsAnyOf <- decodeAsOption[ANY_OF_REPRESENTATION](groupsSection)(AnyOfGroupsRule.NameInsideGroupsSubruleSection.name.value)
          groupsNotAllOf <- decodeAsOption[NOT_ALL_OF_REPRESENTATION](groupsSection)(NotAllOfGroupsRule.NameInsideGroupsSubruleSection.name.value)
          groupsNotAnyOf <- decodeAsOption[NOT_ANY_OF_REPRESENTATION](groupsSection)(NotAnyOfGroupsRule.NameInsideGroupsSubruleSection.name.value)
        } yield (groupsAllOf, groupsAnyOf, groupsNotAllOf, groupsNotAnyOf)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .map(resultFromLogic)

  private def legacyWithoutGroupsSectionDecoder[T <: Rule](implicit ruleName: RuleName[T]): SyncDecoder[GroupsLogicDecodingResult[RULE_REPRESENTATION]] =
    Decoder
      .instance { c =>
        val section = c
        for {
          groupsAllOf <- decodeAsOption[ALL_OF_REPRESENTATION](section)(
            AllOfGroupsRule.NameV1.name.value,
            AllOfGroupsRule.NameV2.name.value,
          )
          groupsAnyOf <- decodeAsOption[ANY_OF_REPRESENTATION](section)(
            AnyOfGroupsRule.NameV1.name.value,
            AnyOfGroupsRule.NameV2.name.value,
            AnyOfGroupsRule.NameV3.name.value,
            AnyOfGroupsRule.NameV4.name.value,
          )
          groupsNotAllOf <- decodeAsOption[NOT_ALL_OF_REPRESENTATION](section)(
            NotAllOfGroupsRule.NameV1.name.value
          )
          groupsNotAnyOf <- decodeAsOption[NOT_ANY_OF_REPRESENTATION](section)(
            NotAnyOfGroupsRule.NameV1.name.value
          )
        } yield (groupsAllOf, groupsAnyOf, groupsNotAllOf, groupsNotAnyOf)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .map(resultFromLogic)

  private def resultFromLogic[T <: Rule](logic: (Option[(ALL_OF_REPRESENTATION, String)], Option[(ANY_OF_REPRESENTATION, String)], Option[(NOT_ALL_OF_REPRESENTATION, String)], Option[(NOT_ANY_OF_REPRESENTATION, String)]))
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
          List(groupsAllOfOpt, groupsAnyOfOpt, groupsNotAllOfOpt, groupsNotAnyOfOpt).flatten.map(_._2),
        )
    }

  private def decodeAsOption[REPRESENTATION: Decoder](c: ACursor)(field: String, fields: String*) = {
    val (cursor, key) = c.downFieldsWithKey(field, fields: _*)
    cursor.as[Option[REPRESENTATION]].map(_.map((_, key)))
  }

  private[rules] def errorMsgNoGroupsList[R <: Rule](ruleName: RuleName[R]) = {
    s"${ruleName.show} rule requires to define 'groups_or'/'groups_and'/'groups_not_any_of'/'groups_not_all_of' arrays"
  }

  private[rules] def errorMsgOnlyOneGroupsList[R <: Rule](ruleName: RuleName[R]) = {
    s"${ruleName.show} rule requires to define 'groups_or'/'groups_and'/'groups_not_any_of'/'groups_not_all_of' arrays (but not all)"
  }

}

object GroupsLogicRepresentationDecoder {
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
