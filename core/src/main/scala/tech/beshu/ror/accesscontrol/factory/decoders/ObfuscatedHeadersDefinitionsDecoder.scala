package tech.beshu.ror.accesscontrol.factory.decoders

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.logging.ObfuscatedHeaders

object ObfuscatedHeadersDefinitionsDecoder {

  import tech.beshu.ror.accesscontrol.factory.decoders.common.headerName

  lazy val instance: Decoder[Option[ObfuscatedHeaders]] =
    Decoder.forProduct1[Option[ObfuscatedHeaders], Option[Set[Header.Name]]]("obfuscated_headers")(_.map(ObfuscatedHeaders.apply))
}
