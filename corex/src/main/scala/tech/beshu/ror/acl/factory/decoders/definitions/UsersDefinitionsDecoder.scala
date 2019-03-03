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
import cats.implicits._
import io.circe.{ACursor, Decoder, HCursor}
import tech.beshu.ror.acl.domain.{Group, User}
import tech.beshu.ror.acl.blocks.definitions._
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.authenticationRuleDecoderBy
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.factory.decoders.common._
import tech.beshu.ror.acl.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}

object UsersDefinitionsDecoder {

  def instance(authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
               authProxyDefinitions: Definitions[ProxyAuth],
               jwtDefinitions: Definitions[JwtDef],
               rorKbnDefinitions: Definitions[RorKbnDef]): ADecoder[Id, Definitions[UserDef]] = {
    implicit val userDefDecoder: SyncDecoder[UserDef] = SyncDecoderCreator
      .from(UsersDefinitionsDecoder.userDefDecoder(authenticationServiceDefinitions, authProxyDefinitions, jwtDefinitions, rorKbnDefinitions))
    DefinitionsBaseDecoder.instance[Id, UserDef]("users")
  }

  private implicit def userDefDecoder(implicit authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                      authProxyDefinitions: Definitions[ProxyAuth],
                                      jwtDefinitions: Definitions[JwtDef],
                                      rorKbnDefinitions: Definitions[RorKbnDef]): Decoder[UserDef] = {
    SyncDecoderCreator
      .instance { c =>
        val usernameKey = "username"
        val groupsKey = "groups"
        for {
          username <- c.downField(usernameKey).as[User.Id]
          groups <- c.downField(groupsKey).as[NonEmptySet[Group]]
          rule <- tryDecodeAuthRule(removeKeysFromCursor(c, Set(usernameKey, groupsKey)), username)
        } yield UserDef(username, groups, rule)
      }
      .withError(DefinitionsLevelCreationError.apply, Message("User definition malformed"))
      .decoder
  }

  private def tryDecodeAuthRule(adjustedCursor: ACursor, username: User.Id)
                               (implicit authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                authProxyDefinitions: Definitions[ProxyAuth],
                                jwtDefinitions: Definitions[JwtDef],
                                rorKbnDefinitions: Definitions[RorKbnDef]) = {
    adjustedCursor.keys.map(_.toList) match {
      case Some(key :: Nil) =>
        val decoder = authenticationRuleDecoderBy(
          Rule.Name(key),
          authenticationServiceDefinitions,
          authProxyDefinitions,
          jwtDefinitions,
          rorKbnDefinitions
        ) match {
          case Some(authRuleDecoder) => authRuleDecoder
          case None => DecoderHelpers.failed[AuthenticationRule](
            DefinitionsLevelCreationError(Message(s"Rule $key is not authentication rule"))
          )
        }
        decoder.tryDecode(adjustedCursor.downField(key))
          .left.map(_.overrideDefaultErrorWith(DefinitionsLevelCreationError(Message(s"Cannot parse '$key' rule declared in user '${username.show}' definition"))))
      case Some(keys) =>
        Left(DecodingFailureOps.fromError(
          DefinitionsLevelCreationError(Message(s"Only one authentication should be defined for user ['${username.show}']. Found ${keys.mkString(", ")}"))
        ))
      case None | Some(Nil) =>
        Left(DecodingFailureOps.fromError(
          DefinitionsLevelCreationError(Message(s"No authentication method defined for user ['${username.show}']"))
        ))
    }
  }

  private def removeKeysFromCursor(cursor: HCursor, keys: Set[String]) = {
    cursor.withFocus(_.mapObject(_.filterKeys(key => !keys.contains(key))))
  }
}
