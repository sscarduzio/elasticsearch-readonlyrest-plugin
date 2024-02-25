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
package tech.beshu.ror.accesscontrol.factory.decoders.definitions

import cats.Id
import cats.data.NonEmptyList
import cats.implicits._
import io.circe.{ACursor, Decoder, HCursor, Json}
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.{GroupMappings, Mode}
import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.GroupsOrRule
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIdLike, GroupName, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{DefinitionsLevelCreationError, ValueLevelCreationError}
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.ruleDecoders.{usersDefinitionsAllowedRulesDecoderBy, withUserIdParamsCheck}
import tech.beshu.ror.accesscontrol.factory.decoders.rules._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers.failed
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

object UsersDefinitionsDecoder {

  import tech.beshu.ror.accesscontrol.factory.decoders.definitions.UsersDefinitionsDecoder.GroupsDecoder._

  def instance(authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
               authorizationServiceDefinitions: Definitions[ExternalAuthorizationService],
               authProxyDefinitions: Definitions[ProxyAuth],
               jwtDefinitions: Definitions[JwtDef],
               rorKbnDefinitions: Definitions[RorKbnDef],
               ldapServiceDefinitions: Definitions[LdapService],
               impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]],
               mocksProvider: MocksProvider,
               globalSettings: GlobalSettings): ADecoder[Id, Definitions[UserDef]] = {
    implicit val userDefDecoder: SyncDecoder[UserDef] =
      SyncDecoderCreator
        .instance { c =>
          val usernameKey = "username"
          val groupsKey = "groups"
          for {
            usernamePatterns <- c.downFieldAs[UserIdPatterns](usernameKey)
            rules <- {
              val rulesDecoder = userDefRulesDecoder(
                usernamePatterns,
                authenticationServiceDefinitions,
                authorizationServiceDefinitions,
                authProxyDefinitions,
                jwtDefinitions,
                rorKbnDefinitions,
                ldapServiceDefinitions,
                impersonatorsDefinitions,
                mocksProvider,
                globalSettings
              )
              rulesDecoder.tryDecode(c.withoutKeys(Set(usernameKey, groupsKey)))
            }
            mode <- {
              implicit val mDecoder: Decoder[Mode] = modeDecoderFrom(rules, usernamePatterns)
              c.downField(groupsKey).as[UserDef.Mode]
            }
          } yield UserDef(usernamePatterns, mode)
        }
        .withError(DefinitionsLevelCreationError.apply, Message("User definition malformed"))
    DefinitionsBaseDecoder.instance[Id, UserDef]("users")
  }

  private implicit val userIdPatternsDecoder: Decoder[UserIdPatterns] =
    Decoder[UniqueNonEmptyList[UserIdPattern]].map(UserIdPatterns.apply)

  private def userDefRulesDecoder(usernamePatterns: UserIdPatterns,
                                  authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                  authorizationServiceDefinitions: Definitions[ExternalAuthorizationService],
                                  authProxyDefinitions: Definitions[ProxyAuth],
                                  jwtDefinitions: Definitions[JwtDef],
                                  rorKbnDefinitions: Definitions[RorKbnDef],
                                  ldapServiceDefinitions: Definitions[LdapService],
                                  impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]],
                                  mocksProvider: MocksProvider,
                                  globalSettings: GlobalSettings): Decoder[List[Rule]] = Decoder.instance { c =>
    type RuleDecoders = List[RuleDecoder[Rule]]
    val ruleNames = c.keys.toList.flatten.map(Rule.Name.apply)
    val ruleDecoders = ruleNames.foldLeft(Either.right[Message, RuleDecoders](List.empty)) {
      case (failure@Left(_), _) => failure
      case (Right(decoders), ruleName) =>
        usersDefinitionsAllowedRulesDecoderBy(
          ruleName,
          authenticationServiceDefinitions,
          authorizationServiceDefinitions,
          authProxyDefinitions,
          jwtDefinitions,
          rorKbnDefinitions,
          ldapServiceDefinitions,
          impersonatorsDefinitions,
          mocksProvider,
          globalSettings
        ) match {
          case Some(ruleDecoder) => Right(ruleDecoder :: decoders)
          case None => Left(Message(s"Unknown rule '${ruleName.show}' in users definitions section"))
        }
    }
    ruleDecoders
      .left.map(error => DecodingFailureOps.fromError(DefinitionsLevelCreationError(error)))
      .map { decoders =>
        decoders.map(withUserIdParamsCheck(_, usernamePatterns, globalSettings, decodingFailure))
      }
      .flatMap { decoders =>
        val emptyAcc: (ACursor, Decoder.Result[List[Rule]]) = (c, Right(List.empty))
        decoders
          .foldLeft(emptyAcc) {
            case ((modifiedCursor, left@Left(_)), _) =>
              (modifiedCursor, left)
            case ((modifiedCursor, Right(rules)), decoder) =>
              decoder.apply(c) match {
                case Right(RuleDecoder.Result(ruleWithVariableUsage, unconsumedCursor)) =>
                  (unconsumedCursor, Right(ruleWithVariableUsage.rule :: rules))
                case Left(failure) =>
                  (modifiedCursor, Left(failure))
              }
          }
          .sequence
      }
      .map { case (_, rules) => rules }
  }

  private def modeDecoderFrom(rules: List[Rule],
                              usernamePatterns: UserIdPatterns) = {
    rules match {
      case Nil =>
        failed[Mode](DefinitionsLevelCreationError(Message(s"No authentication method defined for [${usernamePatterns.show}] in users definition section")))
      case first :: Nil =>
        oneRuleModeFrom(first)
      case first :: second :: Nil =>
        twoRulesModeFrom(first, second)
      case moreThanTwoRules =>
        val ruleNamesStr = moreThanTwoRules.map(_.name.show).mkString(",")
        failed[Mode](DefinitionsLevelCreationError(Message(s"Too many rules defined for [${usernamePatterns.show}] in users definition section: $ruleNamesStr")))
    }
  }

  private def oneRuleModeFrom(rule: Rule): Decoder[Mode] = rule match {
    case _: GroupsOrRule =>
      failed(DefinitionsLevelCreationError(Message(s"Cannot use '${rule.name.show}' rule in users definition section")))
    case r: AuthRule =>
      Decoder[GroupMappings].map(UserDef.Mode.WithGroupsMapping(Auth.SingleRule(r), _))
    case r: AuthenticationRule =>
      GroupsDecoder
        .groupsDecoder
        .map(UserDef.Mode.WithoutGroupsMapping(r, _))
    case other =>
      failed(DefinitionsLevelCreationError(Message(s"Cannot use '${other.name.show}' rule in users definition section")))
  }

  private def twoRulesModeFrom(rule1: Rule, rule2: Rule): Decoder[Mode] =
    (rule1, rule2) match {
      case (r1: AuthRule, r2: AuthenticationRule) =>
        errorFor(r1, r2)
      case (r1: AuthRule, r2: AuthorizationRule) =>
        errorFor(r1, r2)
      case (r1: AuthenticationRule, r2: AuthRule) =>
        errorFor(r2, r1)
      case (r1: AuthorizationRule, r2: AuthRule) =>
        errorFor(r2, r1)
      case (r1: AuthenticationRule, r2: AuthorizationRule) =>
        Decoder[GroupMappings].map(mappings =>
          UserDef.Mode.WithGroupsMapping(Auth.SeparateRules(r1, r2), mappings)
        )
      case (r1: AuthorizationRule, r2: AuthenticationRule) =>
        Decoder[GroupMappings].map(mappings =>
          UserDef.Mode.WithGroupsMapping(Auth.SeparateRules(r2, r1), mappings)
        )
      case (r1, r2) =>
        errorFor(r1, r2)
    }

  private def errorFor[T](authRule: Rule, authenticationRule: AuthenticationRule) = {
    failed[T](DefinitionsLevelCreationError(Message(
      s"""Users definition section external groups mapping feature allows for single rule with authentication
         | and authorization or two rules which handle authentication and authorization separately. '${authRule.name.show}'
         | is an authentication with authorization rule and '${authenticationRule.name.show}' is and authentication only rule.
         | Cannot use them both in this context.""".stripMargin
    )))
  }

  private def errorFor[T](authRule: Rule, authorizationRule: AuthorizationRule) = {
    failed[T](DefinitionsLevelCreationError(Message(
      s"""Users definition section external groups mapping feature allows for single rule with authentication
         | and authorization or two rules which handle authentication and authorization separately. '${authRule.name.show}'
         | is an authentication with authorization rule and '${authorizationRule.name.show}' is and authorization only rule.
         | Cannot use them both in this context.""".stripMargin
    )))
  }

  private def errorFor[T](rule1: Rule, rule2: Rule) = {
    failed[T](DefinitionsLevelCreationError(Message(
      s"""Users definition section external groups mapping feature allows for single rule with authentication
         | and authorization or two rules which handle authentication and authorization separately. '${rule1.name.show}'
         | and '${rule2.name.show}' should be authentication and authorization rules""".stripMargin
    )))
  }

  private def failure(msg: Message) = Left(decodingFailure(msg))

  private def decodingFailure(msg: Message) = DecodingFailureOps.fromError(DefinitionsLevelCreationError(msg))

  private object GroupsDecoder {

    private object GroupMappingKeys {
      val id: String = "id"
      val name: String = "name"
      val localGroup: String = "local_group"
      val externalGroups: String = "external_group_ids"

      val simpleMappingRequiredKeys: Set[String] = Set(localGroup)
      val advancedMappingRequiredKeys: Set[String] = Set(localGroup, externalGroups)
    }

    implicit lazy val groupsDecoder: Decoder[UniqueNonEmptyList[Group]] = {
      groupsSimpleDecoder.or(structuredGroupsDecoder)
    }

    // supported formats for 'groups' key:
    // * array of strings (local group IDs) - simple groups mapping
    // * array of objects (object with one key as local group ID - value is array of strings (external group IDs)) - advanced group mapping
    // * array of objects (object with 'id' and 'name' keys) - simple groups mapping
    // * array of objects (object with `local_group` and 'external_group_ids' keys) -> advanced group mapping
    implicit lazy val groupMappingsDecoder: Decoder[GroupMappings] = Decoder.instance { c =>
      for {
        mappingsJsons <- c.values
          .toRight("Unknown format of `groups`")
          .flatMap(values => NonEmptyList.fromList(values.toList).toRight("Non empty list of group mappings is required"))
          .leftMap(msg => decodingFailure(Message(msg)))
        mappingsDecoder = mappingsJsons match {
          case groupMappings if haveSimpleFormatWithGroupIds(groupMappings) =>
            groupsSimpleDecoder
              .map(GroupMappings.Simple)
              .widen[GroupMappings]
          case groupMappings if haveAdvancedFormatWithStructuredGroups(groupMappings) =>
            advancedGroupMappingsDecoder(structuredLocalGroupToExternalGroupsMappingDecoder)
              .widen[GroupMappings]
          case groupMappings if haveSimpleFormatWithStructuredGroups(groupMappings) =>
            structuredGroupsDecoder
              .map(GroupMappings.Simple)
              .widen[GroupMappings]
          case _ =>
            advancedGroupMappingsDecoder(localGroupToExternalGroupsMappingDecoder)
              .widen[GroupMappings]
        }
        mappings <- mappingsDecoder.apply(c)
      } yield mappings
    }

    private def haveSimpleFormatWithGroupIds(groupMappings: NonEmptyList[Json]): Boolean = {
      groupMappings.forall(_.isString)
    }

    private def haveSimpleFormatWithStructuredGroups(groupMappings: NonEmptyList[Json]): Boolean = {
      groupMappings.forall { mapping =>
        objectContainsKeys(mapping, GroupMappingKeys.simpleMappingRequiredKeys)
      }
    }

    private def haveAdvancedFormatWithStructuredGroups(groupMappings: NonEmptyList[Json]): Boolean = {
      groupMappings.forall { mapping =>
        objectContainsKeys(mapping, GroupMappingKeys.advancedMappingRequiredKeys)
      }
    }

    private def objectContainsKeys(json: Json, keys: Set[String]): Boolean = {
      json.isObject && json.hcursor.keys.forall(objectKeys => keys.subsetOf(objectKeys.toSet))
    }

    private def advancedGroupMappingsDecoder(implicit mappingDecoder: Decoder[GroupMappings.Advanced.Mapping]): Decoder[GroupMappings.Advanced] =
      Decoder[List[GroupMappings.Advanced.Mapping]]
        .toSyncDecoder
        .emapE { list =>
          UniqueNonEmptyList.fromIterable(list) match {
            case Some(mappings) => Right(GroupMappings.Advanced(mappings))
            case None => Left(ValueLevelCreationError(Message("Non empty list of groups mappings is required")))
          }
        }
        .decoder

    private val structuredLocalGroupToExternalGroupsMappingDecoder: Decoder[GroupMappings.Advanced.Mapping] =
      Decoder
        .instance { c =>
          for {
            id <- c.downField(GroupMappingKeys.localGroup).downFieldAs[GroupId](GroupMappingKeys.id)
            name <- c.downField(GroupMappingKeys.localGroup).downFieldAs[GroupName](GroupMappingKeys.name)
            externalGroupIds <- c.downFieldAs[UniqueNonEmptyList[GroupIdLike]](GroupMappingKeys.externalGroups)
          } yield {
            val localGroup = Group(id, name)
            GroupMappings.Advanced.Mapping(localGroup, externalGroupIds)
          }
        }

    private val localGroupToExternalGroupsMappingDecoder: Decoder[GroupMappings.Advanced.Mapping] =
      Decoder
        .instance { c =>
          c.keys.map(_.toList) match {
            case Some(key :: Nil) =>
              for {
                localGroup <- Decoder[GroupId].tryDecode(HCursor.fromJson(Json.fromString(key))).map(Group.from)
                externalGroups <- c.downFieldAs[UniqueNonEmptyList[GroupIdLike]](key)
              } yield {
                GroupMappings.Advanced.Mapping(localGroup, externalGroups)
              }
            case Some(Nil) | None =>
              failure(Message(s"Groups mapping should have exactly one YAML key"))
            case Some(keys) =>
              failure(Message(s"Groups mapping should have exactly one YAML key, but several were defined: [${keys.mkString(",")}]"))
          }
        }

    private val structuredGroupsDecoder: Decoder[UniqueNonEmptyList[Group]] = {
      implicit val groupDecoder: Decoder[Group] = Decoder.instance { c =>
        for {
          id <- c.downFieldAs[GroupId](GroupMappingKeys.id)
          name <- c.downFieldAs[GroupName](GroupMappingKeys.name)
        } yield Group(id, name)
      }

      SyncDecoderCreator
        .from(DecoderHelpers.decodeUniqueNonEmptyList[Group])
        .withError(ValueLevelCreationError(Message("Non empty list of groups is required")))
        .decoder
    }

    private val groupsSimpleDecoder: Decoder[UniqueNonEmptyList[Group]] = {
      Decoder[UniqueNonEmptyList[GroupId]]
        .map(groupIds => UniqueNonEmptyList.unsafeFromIterable(groupIds.toList.map(Group.from)))
    }
  }
}
