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
package tech.beshu.ror.configuration

import java.io.{File => JFile}
import java.nio.file.Path

import better.files.File
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers._

final case class RorIndexNameConfiguration(name: IndexName)

object RorIndexNameConfiguration extends LoadFromEsConfig {

  private val defaultIndexName = IndexName.fromUnsafeString(".readonlyrest")

  def load(esConfig: File): Task[Either[MalformedSettings, RorIndexNameConfiguration]] = Task {
    loadConfigFromFile[RorIndexNameConfiguration](esConfig, "Custom ROR index name")
  }

  def load(esConfigFolderPath: Path): Task[Either[MalformedSettings, RorIndexNameConfiguration]] = {
    load(File(new JFile(esConfigFolderPath.toFile, "elasticsearch.yml").toPath))
  }

  private implicit val rorIndexNameConfigurationDecoder: Decoder[RorIndexNameConfiguration] = {
    Decoder.instance { c =>
      val oneLine = c.downField("readonlyrest.custom_index_name").as[Option[NonEmptyString]]
      val twoLines =  c.downField("readonlyrest").downField("custom_index_name").as[Option[NonEmptyString]]
      val customIndexName = (oneLine.toOption.flatten, twoLines.toOption.flatten) match {
        case (Some(result), _) => IndexName(result)
        case (_, Some(result)) => IndexName(result)
        case (_, _) => defaultIndexName
      }
      Right(RorIndexNameConfiguration(customIndexName))
    }
  }
}
