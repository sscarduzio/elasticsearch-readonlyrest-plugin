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
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.domain.{Group, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.language.implicitConversions

object UsersDefinitionsDecoder {

  def instance(caseMappingEquality: UserIdCaseMappingEquality)
              (authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
               authProxyDefinitions: Definitions[ProxyAuth],
               jwtDefinitions: Definitions[JwtDef],
               ldapDefinitions: Definitions[LdapService],
               rorKbnDefinitions: Definitions[RorKbnDef],
               impersonatorDefs: Definitions[ImpersonatorDef]): ADecoder[Id, Definitions[UserDef]] = {
    implicit val userDefDecoder: SyncDecoder[UserDef] =
      SyncDecoderCreator.from(
        UsersDefinitionsDecoder
          .userDefDecoder(caseMappingEquality)(
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

  private implicit def userDefDecoder(caseMappingEquality: UserIdCaseMappingEquality)
                                     (implicit authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                      authProxyDefinitions: Definitions[ProxyAuth],
                                      jwtDefinitions: Definitions[JwtDef],
                                      ldapDefinitions: Definitions[LdapService],
                                      rorKbnDefinitions: Definitions[RorKbnDef],
                                      impersonatorDefs: Definitions[ImpersonatorDef]): Decoder[UserDef] = {
    implicit val someImpersonatorDefs = Some(impersonatorDefs)
    SyncDecoderCreator
      .instance { c =>
        val usernameKey = "username"
        val groupsKey = "groups"
        for {
          usernamePatterns <- c.downField(usernameKey).as[UniqueNonEmptyList[UserIdPattern]].map(UserIdPatterns.apply)
          groups <- c.downField(groupsKey).as[UniqueNonEmptyList[Group]]
          ruleWithVariableUsage <- c.withoutKeys(Set(usernameKey, groupsKey))
            .tryDecodeAuthRule(usernamePatterns, caseMappingEquality)
            .left.map(m => DecodingFailureOps.fromError(DefinitionsLevelCreationError(m)))
        } yield UserDef(usernamePatterns, groups, ruleWithVariableUsage.rule)
      }
      .withError(DefinitionsLevelCreationError.apply, Message("User definition malformed"))
      .decoder
  }
}
