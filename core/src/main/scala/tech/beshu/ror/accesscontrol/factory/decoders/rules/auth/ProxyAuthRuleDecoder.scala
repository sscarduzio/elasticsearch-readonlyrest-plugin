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

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.{ImpersonatorDef, ProxyAuth}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ProxyAuthRule
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Header, User}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.userIdDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.ProxyAuthDefinitionsDecoder._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.OptionalImpersonatorDefinitionOps
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, DecodingFailureOps}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class ProxyAuthRuleDecoder(authProxiesDefinitions: Definitions[ProxyAuth],
                           impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                           mocksProvider: MocksProvider,
                           implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleBaseDecoderWithoutAssociatedFields[ProxyAuthRule] {

  override protected def decoder: Decoder[RuleDefinition[ProxyAuthRule]] = {
    ProxyAuthRuleDecoder.simpleSettingsDecoder
      .or(ProxyAuthRuleDecoder.extendedSettingsDecoder(authProxiesDefinitions))
      .map(settings => RuleDefinition.create(
        new ProxyAuthRule(
          settings,
          impersonatorsDef.toImpersonation(mocksProvider),
          caseMappingEquality
        )
      ))
  }
}

private object ProxyAuthRuleDecoder {

  import cats.implicits._

  private val defaultUserHeaderName: Header.Name = Header.Name.xForwardedUser

  private def simpleSettingsDecoder: Decoder[ProxyAuthRule.Settings] =
    DecoderHelpers
      .decoderStringLikeOrUniqueNonEmptyList[User.Id]
      .map(ProxyAuthRule.Settings(_, defaultUserHeaderName))

  private def extendedSettingsDecoder(authProxiesDefinitions: Definitions[ProxyAuth]): Decoder[ProxyAuthRule.Settings] =
    Decoder.instance { c =>
      for {
        users <- uniqueNonEmptyListOfUserIdsDecoder.tryDecode(c.downField("users"))
        authProxyName <- c.downField("proxy_auth_config").as[Option[ProxyAuth.Name]]
        settings <- authProxyName match {
          case None => Right(ProxyAuthRule.Settings(users, defaultUserHeaderName))
          case Some(name) =>
            authProxiesDefinitions.items.find(_.id === name) match {
              case Some(proxy) => Right(ProxyAuthRule.Settings(users, proxy.userIdHeader))
              case None => Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message(s"Cannot find proxy auth with name: ${name.show}"))))
            }
        }
      } yield settings
    }

  implicit def uniqueNonEmptyListOfUserIdsDecoder: Decoder[UniqueNonEmptyList[User.Id]] =
    DecoderHelpers.decodeNonEmptyStringLikeOrUniqueNonEmptyList(User.Id.apply)

}
