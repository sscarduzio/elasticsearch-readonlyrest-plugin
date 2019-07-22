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
import tech.beshu.ror.acl.blocks.definitions._
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapService
import tech.beshu.ror.acl.domain.{Group, User}
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.decoders.common._
import tech.beshu.ror.acl.utils.CirceOps.{ACursorOps, HCursorOps, _}
import tech.beshu.ror.acl.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}

object UsersDefinitionsDecoder {

  def instance(authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
               authProxyDefinitions: Definitions[ProxyAuth],
               jwtDefinitions: Definitions[JwtDef],
               ldapDefinitions: Definitions[LdapService],
               rorKbnDefinitions: Definitions[RorKbnDef],
               impersonatorDefs: Definitions[ImpersonatorDef]): ADecoder[Id, Definitions[UserDef]] = {
    implicit val userDefDecoder: SyncDecoder[UserDef] =
      SyncDecoderCreator.from(
        UsersDefinitionsDecoder
          .userDefDecoder(
            authenticationServiceDefinitions,
            authProxyDefinitions,
            jwtDefinitions,
            ldapDefinitions,
            rorKbnDefinitions,
            impersonatorDefs
          )
      )
    DefinitionsBaseDecoder.instance[Id, UserDef]("users")
  }

  private implicit def userDefDecoder(implicit authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                      authProxyDefinitions: Definitions[ProxyAuth],
                                      jwtDefinitions: Definitions[JwtDef],
                                      ldapDefinitions: Definitions[LdapService],
                                      rorKbnDefinitions: Definitions[RorKbnDef],
                                      impersonatorDefs: Definitions[ImpersonatorDef]): Decoder[UserDef] = {
    implicit val _ = Some(impersonatorDefs)
    SyncDecoderCreator
      .instance { c =>
        val usernameKey = "username"
        val groupsKey = "groups"
        for {
          username <- c.downField(usernameKey).as[User.Id]
          groups <- c.downField(groupsKey).as[NonEmptySet[Group]]
          rule <- c.withoutKeys(Set(usernameKey, groupsKey))
            .tryDecodeAuthRule(username)
            .left.map(m => DecodingFailureOps.fromError(DefinitionsLevelCreationError(m)))
        } yield UserDef(username, groups, rule)
      }
      .withError(DefinitionsLevelCreationError.apply, Message("User definition malformed"))
      .decoder
  }

}
