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
package tech.beshu.ror.tools.core.patches.base

import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, Encoder}
import just.semver.SemVer
import os.Path
import tech.beshu.ror.tools.core.patches.internal.FilePatch.FilePatchMetadata
import tech.beshu.ror.tools.core.patches.internal.RorPluginDirectory.EsPatchMetadata

import java.net.URLDecoder

object EsPatchMetadataCodec {

  private implicit val pathCodec: Codec[os.Path] = Codec.from[os.Path](
    decodeA = Decoder.decodeString.map[os.Path](str => Path(URLDecoder.decode(str, "UTF-8"))),
    encodeA = Encoder.encodeString.contramap(_.toString),
  )

  private implicit val semVerCodec: Codec[SemVer] = Codec.from[SemVer](
    decodeA = Decoder.decodeString.map[SemVer](SemVer.parseUnsafe),
    encodeA = Encoder.encodeString.contramap(_.render),
  )

  private implicit val filePatchMetadataCodec: Codec[FilePatchMetadata] = deriveCodec

  private val codec: Codec[EsPatchMetadata] =
    Codec.forProduct3[EsPatchMetadata, String, SemVer, List[FilePatchMetadata]](
      "rorVersion",
      "esVersion",
      "patchedFilesMetadata",
    )(EsPatchMetadata.apply)(m => (m.rorVersion, m.esVersion, m.patchedFilesMetadata))

  def encode(esPatchMetadata: EsPatchMetadata): String = {
    codec.apply(esPatchMetadata).spaces2
  }

  def decode(str: String): Either[String, EsPatchMetadata] = {
    for {
      json <- io.circe.parser.parse(str).left.map(_.message)
      result <- codec.decodeJson(json).left.map(_.message)
    } yield result
  }

}
