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
package tech.beshu.ror.accesscontrol.factory.decoders.rules

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.domain.{Header, User}
import tech.beshu.ror.accesscontrol.blocks.definitions.ProxyAuth
import tech.beshu.ror.accesscontrol.blocks.rules.ProxyAuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.ProxyAuthDefinitionsDecoder._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.ProxyAuthRuleDecoderHelper.{defaultUserHeaderName, nonEmptySetOfUserIdsDecoder}
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, DecodingFailureOps}

class ProxyAuthRuleDecoder(authProxiesDefinitions: Definitions[ProxyAuth]) extends RuleDecoderWithoutAssociatedFields(
  Decoder.instance { c =>
    for {
      users <- nonEmptySetOfUserIdsDecoder.tryDecode(c.downField("users"))
      authProxyName <- c.downField("proxy_auth_config").as[Option[ProxyAuth.Name]]
      settings <- authProxyName match {
        case None => Right(ProxyAuthRule.Settings(users, defaultUserHeaderName))
        case Some(name) =>
          authProxiesDefinitions.items.find(_.id === name) match {
            case Some(proxy) => Right(ProxyAuthRule.Settings(users, proxy.userIdHeader))
            case None => Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message(s"Cannot find proxy auth with name: ${name.show}"))))
          }
      }
    } yield RuleWithVariableUsageDefinition(new ProxyAuthRule(settings))
  }
)

private object ProxyAuthRuleDecoderHelper {
  val defaultUserHeaderName: Header.Name = Header.Name.xForwardedUser

  implicit val nonEmptySetOfUserIdsDecoder: Decoder[NonEmptySet[User.Id]] =
    DecoderHelpers.decodeNonEmptyStringLikeOrNonEmptySet(User.Id.apply)
}
