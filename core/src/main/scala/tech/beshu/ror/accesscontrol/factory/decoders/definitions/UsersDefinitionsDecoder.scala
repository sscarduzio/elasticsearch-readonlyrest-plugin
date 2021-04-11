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

import java.time.Clock

import cats.Id
import cats.implicits._
import io.circe.{ACursor, DecodingFailure}
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth
import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.domain.{Group, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.ruleDecoders.usersDefinitionsAllowedRulesDecoderBy
import tech.beshu.ror.accesscontrol.factory.decoders.rules._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.providers.UuidProvider
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.language.implicitConversions

object UsersDefinitionsDecoder {

  def instance(authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
               authorizationServiceDefinitions: Definitions[ExternalAuthorizationService],
               authProxyDefinitions: Definitions[ProxyAuth],
               jwtDefinitions: Definitions[JwtDef],
               rorKbnDefinitions: Definitions[RorKbnDef],
               ldapServiceDefinitions: Definitions[LdapService],
               impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]],
               caseMappingEquality: UserIdCaseMappingEquality)
              (implicit clock: Clock,
               uuidProvider: UuidProvider): ADecoder[Id, Definitions[UserDef]] = {
    implicit val userDefDecoder: SyncDecoder[UserDef] =
      SyncDecoderCreator
        .instance { c =>
          val usernameKey = "username"
          val groupsKey = "groups"
          for {
            usernamePatterns <- c.downField(usernameKey).as[UniqueNonEmptyList[UserIdPattern]].map(UserIdPatterns.apply)
            groups <- c.downField(groupsKey).as[UniqueNonEmptyList[Group]]
            mode <- decodeMode(
              c.withoutKeys(Set(usernameKey, groupsKey)),
              usernamePatterns,
              authenticationServiceDefinitions,
              authorizationServiceDefinitions,
              authProxyDefinitions,
              jwtDefinitions,
              rorKbnDefinitions,
              ldapServiceDefinitions,
              impersonatorsDefinitions,
              caseMappingEquality
            )
          } yield UserDef(usernamePatterns, groups, mode)
        }
        .withError(DefinitionsLevelCreationError.apply, Message("User definition malformed"))
    DefinitionsBaseDecoder.instance[Id, UserDef]("users")
  }

  private def decodeMode(cursor: ACursor,
                         usernamePatterns: UserIdPatterns,
                         authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                         authorizationServiceDefinitions: Definitions[ExternalAuthorizationService],
                         authProxyDefinitions: Definitions[ProxyAuth],
                         jwtDefinitions: Definitions[JwtDef],
                         rorKbnDefinitions: Definitions[RorKbnDef],
                         ldapServiceDefinitions: Definitions[LdapService],
                         impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]],
                         caseMappingEquality: UserIdCaseMappingEquality)
                        (implicit clock: Clock,
                         uuidProvider: UuidProvider): Either[DecodingFailure, UserDef.Mode] = {
    type DecodedRules = List[RuleDecoder[_ <: Rule]]
    val ruleNames = cursor.keys.toList.flatten.map(Rule.Name.apply)
    val decodedRules = ruleNames.foldLeft(Either.right[Message, DecodedRules](List.empty)) {
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
          caseMappingEquality
        ) match {
          case Some(ruleDecoder) => Right(ruleDecoder :: decoders)
          case None => Left(Message(s"Unknown rule '${ruleName.show}' in users definitions section"))
        }
    }
    val decodedUserModes = decodedRules match {
      case Right(Nil) =>
        failedDecoder(Message(s"No authentication method defined for [${usernamePatterns.show}] in users definition section"))
      case Right(first :: Nil) =>
        oneRuleModeFrom(first)
      case Right(first :: second :: Nil) =>
        twoRulesModeFrom(first, second)
      case Right(moreThanTwoRules) =>
        val ruleNamesStr = moreThanTwoRules.map(_.ruleName.show).mkString(",")
        failedDecoder(Message(s"Two many rules defined for [${usernamePatterns.show}] in users definition section: $ruleNamesStr"))
      case Left(errorMsg) =>
        failedDecoder(errorMsg)
    }
    decodedUserModes.tryDecode(cursor)
  }

  private def oneRuleModeFrom(ruleDecoder: RuleDecoder[_]) = ruleDecoder match {
    case d: GroupsRuleDecoder =>
      failedDecoder(Message(s"Cannot use '${d.ruleName.show}' rule in users definition section"))
    case d: AuthRuleDecoder[_] =>
      userDefModeFrom(d)
    case d: AuthenticationRuleDecoder[_] =>
      userDefModeFrom(d)
    case other =>
      failedDecoder(Message(s"Cannot use '${other.ruleName.show}' rule in users definition section"))
  }

  private def twoRulesModeFrom(rule1Decoder: RuleDecoder[_ <: Rule],
                               rule2Decoder: RuleDecoder[_ <: Rule]) =
    (rule1Decoder, rule2Decoder) match {
      case (d1: AuthRuleDecoder[_], d2: AuthenticationRuleDecoder[_]) =>
        errorFor(d1, d2)
      case (d1: AuthRuleDecoder[_], d2: AuthorizationRuleDecoder[_]) =>
        errorFor(d1, d2)
      case (d1: AuthenticationRuleDecoder[_], d2: AuthRuleDecoder[_]) =>
        errorFor(d2, d1)
      case (d1: AuthorizationRuleDecoder[_], d2: AuthRuleDecoder[_]) =>
        errorFor(d2, d1)
      case (d1: AuthenticationRuleDecoder[_], d2: AuthorizationRuleDecoder[_]) =>
        userDefModeFrom(d1, d2)
      case (d1: AuthorizationRuleDecoder[_], d2: AuthenticationRuleDecoder[_]) =>
        userDefModeFrom(d2, d1)
      case (d1, d2) =>
        errorFor(d1, d2)
    }

  private def userDefModeFrom(d: AuthenticationRuleDecoder[_ <: AuthenticationRule]) = {
    d.map(r => UserDef.Mode.WithoutGroupsMapping(r.rule))
  }

  private def userDefModeFrom(d: AuthRuleDecoder[_ <: AuthRule]) = {
    d.map(r => UserDef.Mode.WithGroupsMapping(Auth.SingleRule(r.rule)))
  }

  private def userDefModeFrom(d1: AuthenticationRuleDecoder[_ <: AuthenticationRule],
                              d2: AuthorizationRuleDecoder[_ <: AuthorizationRule]) = {
    d1.flatMap { r1 =>
      d2.map { r2 =>
        UserDef.Mode.WithGroupsMapping(Auth.SeparateRules(r1.rule, r2.rule))
      }
    }
  }

  private def errorFor(authRuleDecoder: RuleDecoder[_], authenticationRuleDecoder: AuthenticationRuleDecoder[_]) = {
    failedDecoder(Message(
      s"""Users definition section external groups mapping feature allows for single rule with authentication
         | and authorization or two rules which handle authentication and authorization separately. '${authRuleDecoder.ruleName.show}'
         | is an authentication with authorization rule and '${authenticationRuleDecoder.ruleName.show}' is and authentication only rule.
         | Cannot use them both in this context.""".stripMargin
    ))
  }

  private def errorFor(authRule: RuleDecoder[_], authorizationRuleDecoder: AuthorizationRuleDecoder[_]) = {
    failedDecoder(Message(
      s"""Users definition section external groups mapping feature allows for single rule with authentication
         | and authorization or two rules which handle authentication and authorization separately. '${authRule.ruleName.show}'
         | is an authentication with authorization rule and '${authorizationRuleDecoder.ruleName.show}' is and authorization only rule.
         | Cannot use them both in this context.""".stripMargin
    ))
  }

  private def errorFor(rule1Decoder: RuleDecoder[_], rule2Decoder: RuleDecoder[_]) = {
    failedDecoder(Message(
      s"""Users definition section external groups mapping feature allows for single rule with authentication
         | and authorization or two rules which handle authentication and authorization separately. '${rule1Decoder.ruleName.show}'
         | and '${rule2Decoder.ruleName.show}' should be authentication and authorization rules""".stripMargin
    ))
  }

  private def failedDecoder(msg: Message) = {
    DecoderHelpers.failed[UserDef.Mode](DefinitionsLevelCreationError(msg))
  }
}
