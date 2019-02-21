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
package tech.beshu.ror.acl.factory.decoders.definitions

import io.circe.Decoder
import tech.beshu.ror.acl.domain.Header
import tech.beshu.ror.acl.blocks.definitions.ProxyAuth
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.MalformedValue
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