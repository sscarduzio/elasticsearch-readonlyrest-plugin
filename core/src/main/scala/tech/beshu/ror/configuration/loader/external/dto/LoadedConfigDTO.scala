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
package tech.beshu.ror.configuration.loader.external.dto

import io.circe.Codec
import tech.beshu.ror.configuration.loader.LoadedRorConfig
import tech.beshu.ror.utils.CirceOps.*

sealed trait LoadedConfigDTO {
  def raw: String
}

object LoadedConfigDTO {
  implicit val codec: Codec[LoadedConfigDTO] = codecWithTypeDiscriminator(
    encode = {
      case dto: FILE_CONFIG =>
        derivedEncoderWithType[FILE_CONFIG]("FILE_CONFIG")(dto)
      case dto: FORCED_FILE_CONFIG =>
        derivedEncoderWithType[FORCED_FILE_CONFIG]("FORCED_FILE_CONFIG")(dto)
      case dto: INDEX_CONFIG =>
        derivedEncoderWithType[INDEX_CONFIG]("INDEX_CONFIG")(dto)
    },
    decoders = Map(
      "FILE_CONFIG" -> derivedDecoderOfSubtype[LoadedConfigDTO, FILE_CONFIG],
      "FORCED_FILE_CONFIG" -> derivedDecoderOfSubtype[LoadedConfigDTO, FORCED_FILE_CONFIG],
      "INDEX_CONFIG" -> derivedDecoderOfSubtype[LoadedConfigDTO, INDEX_CONFIG],
    )
  )
  def create(o: LoadedRorConfig[String]): LoadedConfigDTO = o match {
    case LoadedRorConfig.FileConfig(value) => FILE_CONFIG(value)
    case LoadedRorConfig.ForcedFileConfig(value) => FORCED_FILE_CONFIG(value)
    case LoadedRorConfig.IndexConfig(indexName, value) => INDEX_CONFIG(indexName.index.name.value, value)
  }
  final case class FILE_CONFIG(raw: String) extends LoadedConfigDTO
  final case class FORCED_FILE_CONFIG(raw: String) extends LoadedConfigDTO
  final case class INDEX_CONFIG(indexName: String, raw: String) extends LoadedConfigDTO
}

