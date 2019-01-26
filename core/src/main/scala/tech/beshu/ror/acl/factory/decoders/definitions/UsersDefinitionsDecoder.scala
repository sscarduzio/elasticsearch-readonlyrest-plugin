package tech.beshu.ror.acl.factory.decoders.definitions

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.{ACursor, Decoder, HCursor}
import tech.beshu.ror.acl.aDomain.{Group, User}
import tech.beshu.ror.acl.blocks.definitions._
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.authenticationRuleDecoderBy
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.CirceOps._

class UsersDefinitionsDecoder(authProxyDefinitions: Definitions[ProxyAuth])
  extends DefinitionsBaseDecoder[UserDef]("users")(
    UsersDefinitionsDecoder.userDefDecoder(authProxyDefinitions)
  )

object UsersDefinitionsDecoder {

  private implicit def userDefDecoder(implicit authProxyDefinitions: Definitions[ProxyAuth]): Decoder[UserDef] = {
    import tech.beshu.ror.acl.factory.decoders.common._
    Decoder
      .instance { c =>
        val usernameKey = "username"
        val groupsKey = "groups"
        for {
          username <- c.downField(usernameKey).as[User.Id]
          groups <- c.downField(groupsKey).as[NonEmptySet[Group]]
          rule <- tryDecodeAuthRule(removeKeysFromCursor(c, Set(usernameKey, groupsKey)), username)
        } yield UserDef(username, groups, rule)
      }
      .withError(DefinitionsLevelCreationError(Message("User definition malformed")))
  }

  private def tryDecodeAuthRule(adjustedCursor: ACursor, username: User.Id)
                               (implicit authProxyDefinitions: Definitions[ProxyAuth]) = {
    adjustedCursor.keys.map(_.toList) match {
      case Some(key :: Nil) =>
        val decoder = authenticationRuleDecoderBy(Rule.Name(key), authProxyDefinitions) match {
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
