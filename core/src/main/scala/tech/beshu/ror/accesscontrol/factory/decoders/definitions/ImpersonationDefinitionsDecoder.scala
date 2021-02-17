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

import cats.data.NonEmptySet
import cats.{Id, Order}
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.Name
import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{ACursorOps, DecoderHelpers, DecodingFailureOps, HCursorOps}
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}

object ImpersonationDefinitionsDecoder {

  def instance(caseMappingEquality: UserIdCaseMappingEquality)
              (implicit authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
               authProxyDefinitions: Definitions[ProxyAuth],
               jwtDefinitions: Definitions[JwtDef],
               ldapDefinitions: Definitions[LdapService],
               rorKbnDefinitions: Definitions[RorKbnDef]): ADecoder[Id, Definitions[ImpersonatorDef]] = {
    implicit val decoder: SyncDecoder[ImpersonatorDef] = SyncDecoderCreator.from(impersonationDefDecoder(caseMappingEquality))
    DefinitionsBaseDecoder.instance[Id, ImpersonatorDef]("impersonation")
  }

  implicit val impersonationDefNameDecoder: Decoder[Name] = DecoderHelpers.decodeStringLikeNonEmpty.map(Name.apply)

  private def impersonationDefDecoder(caseMappingEquality: UserIdCaseMappingEquality)
                                     (implicit authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                      authProxyDefinitions: Definitions[ProxyAuth],
                                      jwtDefinitions: Definitions[JwtDef],
                                      ldapDefinitions: Definitions[LdapService],
                                      rorKbnDefinitions: Definitions[RorKbnDef]): Decoder[ImpersonatorDef] = {
    implicit val orderUserId: Order[User.Id] = caseMappingEquality.toOrder
    implicit val _ = Option.empty[Definitions[ImpersonatorDef]]
    SyncDecoderCreator
      .instance { c =>
        val impersonatorKey = "impersonator"
        val usersKey = "users"
        for {
          impersonator <- c.downField(impersonatorKey).as[User.Id]
          users <- c.downField(usersKey).as[NonEmptySet[User.Id]]
          ruleWithVariableUsage <- c.withoutKeys(Set(impersonatorKey, usersKey))
            .tryDecodeAuthRule(impersonator, caseMappingEquality)
            .left.map(m => DecodingFailureOps.fromError(DefinitionsLevelCreationError(m)))
        } yield ImpersonatorDef(impersonator, ruleWithVariableUsage.rule, users)
      }
      .withError(DefinitionsLevelCreationError.apply, Message("Impersonation definition malformed"))
      .decoder
  }
}
