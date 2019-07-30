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
package tech.beshu.ror.acl.factory.decoders.definitions

import cats.Id
import cats.data.NonEmptySet
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.definitions.JwtDef.Name
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapService
import tech.beshu.ror.acl.blocks.definitions._
import tech.beshu.ror.acl.domain.User
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.decoders.common._
import tech.beshu.ror.acl.utils.CirceOps.{ACursorOps, DecoderHelpers, DecodingFailureOps, HCursorOps}
import tech.beshu.ror.acl.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}

object ImpersonationDefinitionsDecoder {

  def instance(implicit authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
               authProxyDefinitions: Definitions[ProxyAuth],
               jwtDefinitions: Definitions[JwtDef],
               ldapDefinitions: Definitions[LdapService],
               rorKbnDefinitions: Definitions[RorKbnDef]): ADecoder[Id, Definitions[ImpersonatorDef]] = {
    implicit val decoder: SyncDecoder[ImpersonatorDef] = SyncDecoderCreator.from(impersonationDefDecoder)
    DefinitionsBaseDecoder.instance[Id, ImpersonatorDef]("impersonation")
  }

  implicit val impersonationDefNameDecoder: Decoder[Name] = DecoderHelpers.decodeStringLikeNonEmpty.map(Name.apply)

  private def impersonationDefDecoder(implicit authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                      authProxyDefinitions: Definitions[ProxyAuth],
                                      jwtDefinitions: Definitions[JwtDef],
                                      ldapDefinitions: Definitions[LdapService],
                                      rorKbnDefinitions: Definitions[RorKbnDef]): Decoder[ImpersonatorDef] = {
    implicit val _ = Option.empty[Definitions[ImpersonatorDef]]
    SyncDecoderCreator
      .instance { c =>
        val impersonatorKey = "impersonator"
        val usersKey = "users"
        for {
          impersonator <- c.downField(impersonatorKey).as[User.Id]
          users <- c.downField(usersKey).as[NonEmptySet[User.Id]]
          authRule <- c.withoutKeys(Set(impersonatorKey, usersKey))
            .tryDecodeAuthRule(impersonator)
            .left.map(m => DecodingFailureOps.fromError(DefinitionsLevelCreationError(m)))
        } yield ImpersonatorDef(impersonator, authRule, users)
      }
      .withError(DefinitionsLevelCreationError.apply, Message("Impersonation definition malformed"))
      .decoder
  }
}
