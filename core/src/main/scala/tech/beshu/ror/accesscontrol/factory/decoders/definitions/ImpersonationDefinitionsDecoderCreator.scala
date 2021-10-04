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

import cats.implicits._
import cats.{Id, Order}
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.Name
import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.domain.{User, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.ruleDecoders
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{ACursorOps, DecoderHelpers, DecodingFailureOps}
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class ImpersonationDefinitionsDecoderCreator(caseMappingEquality: UserIdCaseMappingEquality,
                                             authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                             authProxyDefinitions: Definitions[ProxyAuth],
                                             jwtDefinitions: Definitions[JwtDef],
                                             ldapDefinitions: Definitions[LdapService],
                                             rorKbnDefinitions: Definitions[RorKbnDef],
                                             mocksProvider: MocksProvider) {

  def create: ADecoder[Id, Definitions[ImpersonatorDef]] = {
    implicit val decoder: SyncDecoder[ImpersonatorDef] = SyncDecoderCreator.from(impersonationDefDecoder)
    DefinitionsBaseDecoder.instance[Id, ImpersonatorDef]("impersonation")
  }

  implicit val impersonationDefNameDecoder: Decoder[Name] = DecoderHelpers.decodeStringLikeNonEmpty.map(Name.apply)

  private def impersonationDefDecoder: Decoder[ImpersonatorDef] = {
    implicit val orderUserId: Order[User.Id] = caseMappingEquality.toOrder
    implicit val _ = Option.empty[Definitions[ImpersonatorDef]]
    SyncDecoderCreator
      .instance { c =>
        val impersonatorKey = "impersonator"
        val usersKey = "users"
        for {
          impersonatorPatterns <- c.downField(impersonatorKey).as[UniqueNonEmptyList[UserIdPattern]].map(UserIdPatterns.apply)
          users <- c.downField(usersKey).as[UniqueNonEmptyList[User.Id]]
          authRuleDecoder = authenticationRulesDecoder(impersonatorPatterns)
          authRule <- authRuleDecoder.tryDecode(c.withoutKeys(Set(impersonatorKey, usersKey)))
        } yield ImpersonatorDef(impersonatorPatterns, authRule, users)
      }
      .withError(DefinitionsLevelCreationError.apply, Message("Impersonation definition malformed"))
      .decoder
  }

  private def authenticationRulesDecoder(userIdPatterns: UserIdPatterns) = Decoder.instance { cursor =>
    cursor
      .keys.toList.flatten
      .map { key =>
        ruleDecoders
          .authenticationRuleDecoderBy(
            Rule.Name(key),
            authenticationServiceDefinitions,
            authProxyDefinitions,
            jwtDefinitions,
            ldapDefinitions,
            rorKbnDefinitions,
            impersonatorsDefinitions = None,
            mocksProvider,
            caseMappingEquality
          ) match {
          case Some(decoder) =>
            ruleDecoders
              .withUserIdParamsCheck(decoder, userIdPatterns, decodingFailure)
              .map(_.rule.rule)
              .apply(cursor)
          case None =>
            Left(decodingFailure(Message("Only an authentication rule can be used in context of 'impersonator' definition")))
        }
      }
      .sequence
      .flatMap {
        case Nil =>
          Left(decodingFailure(Message(s"No authentication method defined for [${userIdPatterns.show}]")))
        case one :: Nil =>
          Right(one)
        case many =>
          Left(decodingFailure(Message(
            s"Only one authentication should be defined for [${userIdPatterns.show}]. Found ${many.map(_.name.show).mkString(", ")}"
          )))
      }
  }

  private def decodingFailure(message: Message) = {
    DecodingFailureOps.fromError(DefinitionsLevelCreationError(message))
  }
}
