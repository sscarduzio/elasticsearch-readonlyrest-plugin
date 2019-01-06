package tech.beshu.ror.acl.factory.decoders

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.rules.ProxyAuthRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.ProxyAuthDecoder._
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, DecodingFailureOps}
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.domain.User
import tech.beshu.ror.commons.orders._
import tech.beshu.ror.acl.factory.decoders.ProxyAuthRuleDecoderHelper.nonEmptySetOfUserIdsDecoder

class ProxyAuthRuleDecoder(authProxies: Set[ProxyAuth]) extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeStringOrJson(
      simpleDecoder = nonEmptySetOfUserIdsDecoder.map(ProxyAuthRule.Settings(_, Header.Name.xForwardedUser)),
      expandedDecoder =
        Decoder.instance { c =>
          for {
            users <- c.downField("users").as[NonEmptySet[User.Id]]
            proxyName <- c.downField("proxy_auth_config").as[ProxyAuth.Name]
            proxy <- authProxies
              .find(_.name === proxyName)
              .map(Right(_))
              .getOrElse(Left(DecodingFailureOps.fromError(RulesLevelCreationError(s"Cannot find proxy auth with name: ${proxyName.show}"))))
          } yield ProxyAuthRule.Settings(users, proxy.userIdHeader)
        }
    )
    .map(new ProxyAuthRule(_))
)

private object ProxyAuthRuleDecoderHelper {
  implicit val nonEmptySetOfUserIdsDecoder: Decoder[NonEmptySet[User.Id]] =
    DecoderHelpers.decodeStringLikeOrNonEmptySet(User.Id.apply)
}
