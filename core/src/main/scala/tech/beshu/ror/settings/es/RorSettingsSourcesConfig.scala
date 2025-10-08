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
package tech.beshu.ror.settings.es

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import monix.eval.Task
import squants.information.Information
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorSettingsFile, RorSettingsIndex}
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.utils.yaml.YamlKeyDecoder

final case class RorSettingsSourcesConfig(settingsIndex: RorSettingsIndex,
                                          settingsFile: RorSettingsFile,
                                          settingsMaxSize: Information)

object RorSettingsSourcesConfig extends YamlFileBasedSettingsLoaderSupport {

  def from(esEnv: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[MalformedSettings, RorSettingsSourcesConfig]] = {
    implicit val decoder: Decoder[RorSettingsSourcesConfig] = for {
      rorSettingsIndex <- decoders.rorSettingsIndexDecoder
      rorSettingsFile <- decoders.rorSettingsFileDecoder(esEnv)
      rorSettingsMaxSize <- decoders.settingsMaxSizeDecoder()
    } yield RorSettingsSourcesConfig(
      rorSettingsIndex,
      rorSettingsFile,
      rorSettingsMaxSize
    )
    loadSetting[RorSettingsSourcesConfig](esEnv, "ROR settings source config")
  }

  private object decoders {

    val rorSettingsIndexDecoder: Decoder[RorSettingsIndex] = {
      implicit val indexNameDecoder: Decoder[RorSettingsIndex] =
        Decoder[NonEmptyString]
          .map(IndexName.Full.apply)
          .map(RorSettingsIndex.apply)
      YamlKeyDecoder[RorSettingsIndex](
        path = NonEmptyList.of("readonlyrest", "settings_index"),
        default = RorSettingsIndex.default
      )
    }

    def rorSettingsFileDecoder(esEnv: EsEnv)
                              (implicit systemContext: SystemContext): Decoder[RorSettingsFile] =
      Decoder.instance(_ => Right(
        RorProperties
          .rorSettingsCustomFile(systemContext.propertiesProvider)
          .getOrElse(RorSettingsFile.default(esEnv))
      ))

    def settingsMaxSizeDecoder()
                              (implicit systemContext: SystemContext): Decoder[Information] = {
      Decoder.instance(_ => Right(
        RorProperties.rorSettingsMaxSize(systemContext.propertiesProvider)
      ))
    }

  }

}