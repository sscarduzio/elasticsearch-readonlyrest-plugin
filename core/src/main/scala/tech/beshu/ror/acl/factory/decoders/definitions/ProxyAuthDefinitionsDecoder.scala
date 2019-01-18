package tech.beshu.ror.acl.factory.decoders.definitions

import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.definitions.{ProxyAuth, ProxyAuthDefinitions}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.ProxyAuthConfigsCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.utils.ScalaExt._
import tech.beshu.ror.acl.show.logs._

object ProxyAuthDefinitionsDecoder {

  implicit val proxyAuthNameDecoder: Decoder[ProxyAuth.Name] = Decoder.decodeString.map(ProxyAuth.Name.apply)

  implicit val proxyAuthDefinitionsDecoder: Decoder[ProxyAuthDefinitions] = {
    implicit val headerNameDecoder: Decoder[Header.Name] = Decoder.decodeString.map(Header.Name.apply)
    implicit val proxyAuthDecoder: Decoder[ProxyAuth] = Decoder
      .forProduct2("name", "user_id_header")(ProxyAuth.apply)
      .withError(value => ProxyAuthConfigsCreationError(MalformedValue(value)))
    DecoderHelpers
      .decodeFieldList[ProxyAuth]("proxy_auth_configs")
      .emapE {
        case NoField => Right(ProxyAuthDefinitions(Set.empty[ProxyAuth]))
        case FieldListValue(Nil) => Left(ProxyAuthConfigsCreationError(Message(s"Proxy auth definitions declared, but no definition found")))
        case FieldListValue(list) =>
          list.map(_.name).findDuplicates match {
            case Nil =>
              Right(ProxyAuthDefinitions(list.toSet))
            case duplicates =>
              Left(ProxyAuthConfigsCreationError(Message(s"Proxy auth definitions must have unique names. Duplicates: ${duplicates.map(_.show).mkString(",")}")))
          }
      }
  }
}
