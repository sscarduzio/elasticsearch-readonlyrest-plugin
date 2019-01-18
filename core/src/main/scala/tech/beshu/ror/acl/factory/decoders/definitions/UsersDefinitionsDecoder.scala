package tech.beshu.ror.acl.factory.decoders.definitions

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.{Decoder, HCursor}
import tech.beshu.ror.acl.aDomain.Group
import tech.beshu.ror.acl.blocks.definitions.{ProxyAuthDefinitions, UserDef, UsersDefinitions}
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.DefinitionsCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.authenticationRuleDecoderBy
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.utils.ScalaExt._

object UsersDefinitionsDecoder {

  implicit def usersDefinitionsDecoder(implicit authProxyDefinitions: ProxyAuthDefinitions): Decoder[UsersDefinitions] = {
    DecoderHelpers
      .decodeFieldList[UserDef]("users")
      .emapE {
        case NoField => Right(UsersDefinitions(Set.empty[UserDef]))
        case FieldListValue(Nil) => Left(DefinitionsCreationError(Message(s"Users definitions section declared, but no definition found")))
        case FieldListValue(list) =>
          list.map(_.username).findDuplicates match {
            case Nil =>
              Right(UsersDefinitions(list.toSet))
            case duplicates =>
              Left(DefinitionsCreationError(Message(s"User definitions must have unique names. Duplicates: ${duplicates.map(_.show).mkString(",")}")))
          }
      }
  }

  private implicit def userDefDecoder(implicit authProxyDefinitions: ProxyAuthDefinitions): Decoder[UserDef] = {
    implicit val usernameDecoder: Decoder[UserDef.Name] = DecoderHelpers.decodeStringLike.map(UserDef.Name.apply)
    implicit val groupDecoder: Decoder[Group] = DecoderHelpers.decodeStringLike.map(Group.apply)
    implicit val groupsDecoder: Decoder[NonEmptySet[Group]] =
      DecoderHelpers
        .decodeStringLikeOrNonEmptySet[Group]
        .withError(DefinitionsCreationError(Message("Non empty list of groups are required")))
    Decoder.instance { c =>
      val usernameKey = "username"
      val groupsKey = "groups"
      for {
        username <- c.downField(usernameKey).as[UserDef.Name]
        groups <- c.downField(groupsKey).as[NonEmptySet[Group]]
        adjustedCursor = removeKeysFromCursor(c, Set(usernameKey, groupsKey))
        ruleDecoder = adjustedCursor.keys.map(_.toList) match {
          case Some(key :: Nil) =>
            authenticationRuleDecoderBy(Rule.Name(key), authProxyDefinitions) match {
              case Some(authRuleDecoder) => authRuleDecoder
              case None => DecoderHelpers.failed[AuthenticationRule](
                DefinitionsCreationError(Message(s"Rule $key is not authentication rule"))
              )
            }
          case Some(keys) =>
            DecoderHelpers.failed[AuthenticationRule](
              DefinitionsCreationError(Message(s"Only one authentication should be defined for user ['${username.show}']. Found ${keys.mkString(",")}"))
            )
          case None | Some(Nil) =>
            DecoderHelpers.failed[AuthenticationRule](
              DefinitionsCreationError(Message(s"No authentication method defined for user ['${username.show}']"))
            )
        }
        rule <- ruleDecoder.tryDecode(adjustedCursor)
      } yield UserDef(username, groups, rule)
    }
  }

  private def removeKeysFromCursor(cursor: HCursor, keys: Set[String]) = {
    cursor.withFocus(_.mapObject(_.filterKeys(key => keys.contains(key))))
  }
}
