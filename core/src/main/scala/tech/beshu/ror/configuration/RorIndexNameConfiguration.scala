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
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers._
import tech.beshu.ror.providers.OsEnvVarsProvider

final case class RorIndexNameConfiguration(index: RorConfigurationIndex)

object RorIndexNameConfiguration extends Logging {

  private val defaultIndexName = IndexName.Full(".readonlyrest")

  def load(esConfigFolderPath: Path): Task[Either[MalformedSettings, RorIndexNameConfiguration]] = {
    load(File(new JFile(esConfigFolderPath.toFile, "elasticsearch.yml").toPath))
  }

  def load(esConfig: File): Task[Either[MalformedSettings, RorIndexNameConfiguration]] = Task {
    new EsConfigFileLoader[RorIndexNameConfiguration]().loadConfigFromFile(esConfig, "Custom ROR index name")
  }

  private implicit val envVarsProvider: OsEnvVarsProvider.type = OsEnvVarsProvider

  private implicit val rorIndexNameConfigurationDecoder: Decoder[RorIndexNameConfiguration] = {
    Decoder.instance { c =>
      val oneLine = c.downField("readonlyrest.settings_index").as[Option[NonEmptyString]]
      val twoLines = c.downField("readonlyrest").downField("settings_index").as[Option[NonEmptyString]]
      val customIndexName = (oneLine.toOption.flatten, twoLines.toOption.flatten) match {
        case (Some(result), _) => IndexName.Full(result)
        case (_, Some(result)) => IndexName.Full(result)
        case (_, _) => defaultIndexName
      }
      Right(RorIndexNameConfiguration(RorConfigurationIndex(customIndexName)))
    }
  }
}
