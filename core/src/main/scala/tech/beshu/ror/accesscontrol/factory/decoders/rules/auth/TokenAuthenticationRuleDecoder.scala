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

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Header, User}
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.rules.OptionalImpersonatorDefinitionOps
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields

final class TokenAuthenticationRuleDecoder(impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                           mocksProvider: MocksProvider,
                                           implicit val caseMappingEquality: UserIdCaseMappingEquality) extends RuleBaseDecoderWithoutAssociatedFields[TokenAuthenticationRule] {
  override protected def decoder: Decoder[Block.RuleDefinition[TokenAuthenticationRule]] =
    TokenAuthenticationRuleDecoder
      .decoder
      .map { settings =>
        RuleDefinition.create(new TokenAuthenticationRule(
          settings,
          impersonatorsDef.toImpersonation(mocksProvider),
          caseMappingEquality
        ))
      }
}

private object TokenAuthenticationRuleDecoder {

  private val decoder: Decoder[TokenAuthenticationRule.Settings] =
    Decoder.instance { c =>
      for {
        token <- c.downField("token").as[NonEmptyString]
        username <- c.downField("username").as[User.Id]
        header <- c.downField("header").as[Option[NonEmptyString]].map(_.map(Header.Name.apply))
      } yield TokenAuthenticationRule.Settings(
        username, token, header
      )
    }

}
