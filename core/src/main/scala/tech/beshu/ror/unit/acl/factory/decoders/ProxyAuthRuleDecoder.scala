package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.unit.acl.blocks.rules.ProxyAuthRule
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.unit.acl.factory.decoders.ProxyAuthDecoder._
import tech.beshu.ror.unit.acl.factory.decoders.ProxyAuthRuleDecoderHelper.{defaultUserHeaderName, nonEmptySetOfUserIdsDecoder}
import tech.beshu.ror.unit.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.unit.acl.utils.CirceOps.{DecoderHelpers, DecodingFailureOps}
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.domain.User
import tech.beshu.ror.commons.orders._

class ProxyAuthRuleDecoder(authProxies: Set[ProxyAuth]) extends RuleDecoderWithoutAssociatedFields(
  Decoder.instance { c =>
    for {
      users <- nonEmptySetOfUserIdsDecoder.tryDecode(c.downField("users"))
      authProxyName <- c.downField("proxy_auth_config").as[Option[ProxyAuth.Name]]
      settings <- authProxyName match {
        case None => Right(ProxyAuthRule.Settings(users, defaultUserHeaderName))
        case Some(name) =>
          authProxies.find(_.name === name) match {
            case Some(proxy) => Right(ProxyAuthRule.Settings(users, proxy.userIdHeader))
            case None => Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message(s"Cannot find proxy auth with name: ${name.show}"))))
          }
      }
    } yield new ProxyAuthRule(settings)
  }
)

private object ProxyAuthRuleDecoderHelper {
  val defaultUserHeaderName: Header.Name = Header.Name.xForwardedUser

  implicit val nonEmptySetOfUserIdsDecoder: Decoder[NonEmptySet[User.Id]] =
    DecoderHelpers.decodeStringLikeOrNonEmptySet(User.Id.apply)
}
