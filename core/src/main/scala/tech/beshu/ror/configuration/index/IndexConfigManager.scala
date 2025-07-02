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
package tech.beshu.ror.configuration.index

import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.configuration.{RawRorConfig, RawRorConfigYamlParser}
import tech.beshu.ror.configuration.index.IndexConfigError.{IndexConfigNotExist, IndexConfigUnknownStructure}
import tech.beshu.ror.configuration.loader.RorConfigLoader.Error
import tech.beshu.ror.configuration.loader.RorConfigLoader.Error.ParsingError
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.es.IndexJsonContentService.{CannotReachContentSource, CannotWriteToIndex, ContentNotFound}

final class IndexConfigManager(indexJsonContentService: IndexJsonContentService,
                               rarRorConfigYamlParser: RawRorConfigYamlParser)
  extends BaseIndexConfigManager[RawRorConfig]
  with Logging {

  override def load(indexName: RorConfigurationIndex): Task[Either[Error[IndexConfigError], RawRorConfig]] = {
    indexJsonContentService
      .sourceOf(indexName.index, Config.rorSettingsIndexConst.id)
      .flatMap {
        case Right(source) =>
          source
            .find(_._1 == Config.rorSettingsIndexConst.settingsKey)
            .map { case (_, rorYamlString) =>
              rarRorConfigYamlParser
                .fromString(rorYamlString)
                .map(_.left.map(ParsingError.apply))
            }
            .getOrElse(configLoaderError(IndexConfigUnknownStructure))
        case Left(CannotReachContentSource) =>
          configLoaderError(IndexConfigNotExist)
        case Left(ContentNotFound) =>
          configLoaderError(IndexConfigNotExist)
      }
  }

  override def save(config: RawRorConfig, rorConfigurationIndex: RorConfigurationIndex): Task[Either[SavingIndexConfigError, Unit]] = {
    indexJsonContentService
      .saveContent(
        rorConfigurationIndex.index,
        Config.rorSettingsIndexConst.id,
        Map(Config.rorSettingsIndexConst.settingsKey -> config.raw)
      )
      .map {
        _.left.map { case CannotWriteToIndex => SavingIndexConfigError.CannotSaveConfig }
      }
  }
}
