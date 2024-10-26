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
package tech.beshu.ror.accesscontrol.factory.decoders.definitions

import cats.Id
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.definitions.ProxyAuth
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder}

object ProxyAuthDefinitionsDecoder {

  lazy val instance: ADecoder[Id, Definitions[ProxyAuth]] = {
    DefinitionsBaseDecoder.instance[Id, ProxyAuth]("proxy_auth_configs")
  }

  implicit val proxyAuthNameDecoder: Decoder[ProxyAuth.Name] = Decoder.decodeString.map(ProxyAuth.Name.apply)

  private implicit val proxyAuthDecoder: SyncDecoder[ProxyAuth] = {
    implicit val headerNameDecoder: Decoder[Header.Name] = DecoderHelpers.decodeStringLikeNonEmpty.map(Header.Name.apply)
    Decoder
      .forProduct2[ProxyAuth, ProxyAuth.Name, Header.Name]("name", "user_id_header")(ProxyAuth.apply)
      .toSyncDecoder
      .withErrorFromJson(value => DefinitionsLevelCreationError(MalformedValue(value)))
  }
}