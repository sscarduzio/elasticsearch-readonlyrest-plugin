package tech.beshu.ror.acl.factory.decoders.rules

import cats.implicits._
import cats.data.NonEmptySet
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.definitions.{ProxyAuth, ProxyAuthDefinitions}
import tech.beshu.ror.acl.blocks.rules.ProxyAuthRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.definitions.ProxyAuthDefinitionsDecoder._
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.ProxyAuthRuleDecoderHelper.{defaultUserHeaderName, nonEmptySetOfUserIdsDecoder}
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, DecodingFailureOps}
import tech.beshu.ror.acl.aDomain.{Header, User}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.show.logs._

class ProxyAuthRuleDecoder(authProxiesDefinitions: ProxyAuthDefinitions) extends RuleDecoderWithoutAssociatedFields(
  Decoder.instance { c =>
    for {
      users <- nonEmptySetOfUserIdsDecoder.tryDecode(c.downField("users"))
      authProxyName <- c.downField("proxy_auth_config").as[Option[ProxyAuth.Name]]
      settings <- authProxyName match {
        case None => Right(ProxyAuthRule.Settings(users, defaultUserHeaderName))
        case Some(name) =>
          authProxiesDefinitions.proxyAuths.find(_.name === name) match {
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
