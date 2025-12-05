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
package tech.beshu.ror.settings.ror.source

import cats.implicits.*
import io.circe.Decoder.Result
import io.circe.{Codec, Decoder, HCursor, Json}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.ror.{RawRorSettings, RawRorSettingsYamlParser}

private [source] class RawRorSettingsCodec(yamlParser: RawRorSettingsYamlParser)
  extends Codec[RawRorSettings] {

  override def apply(c: HCursor): Result[RawRorSettings] =
    Decoder
      .decodeString
      .emap { str =>
        yamlParser
          .fromString(str)
          .left.map(_.show)
      }
      .apply(c)

  override def apply(a: RawRorSettings): Json =
    Json.fromString(a.rawYaml)
}
