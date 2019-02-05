package tech.beshu.ror.acl.factory.decoders.definitions

import io.circe.Decoder
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.acl.blocks.definitions.ProxyAuth
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.utils.CirceOps._

class ProxyAuthDefinitionsDecoder extends DefinitionsBaseDecoder[ProxyAuth]("proxy_auth_configs")(
  ProxyAuthDefinitionsDecoder.proxyAuthDecoder
)

object ProxyAuthDefinitionsDecoder {

  implicit val proxyAuthNameDecoder: Decoder[ProxyAuth.Name] = Decoder.decodeString.map(ProxyAuth.Name.apply)

  private implicit val proxyAuthDecoder: Decoder[ProxyAuth] = {
    implicit val headerNameDecoder: Decoder[Header.Name] = DecoderHelpers.decodeStringLikeNonEmpty.map(Header.Name.apply)
    Decoder
      .forProduct2("name", "user_id_header")(ProxyAuth.apply)
      .withErrorFromJson(value => DefinitionsLevelCreationError(MalformedValue(value)))
  }
}