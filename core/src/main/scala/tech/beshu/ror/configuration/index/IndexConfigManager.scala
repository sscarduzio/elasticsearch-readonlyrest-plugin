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
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.configuration.index.IndexConfigError.{IndexConfigNotExist, IndexConfigUnknownStructure}
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.es.IndexJsonContentService.{CannotReachContentSource, CannotWriteToIndex, ContentNotFound}

class IndexConfigManager(indexJsonContentService: IndexJsonContentService)
  extends Logging {

  def load(indexName: RorConfigurationIndex): Task[Either[ConfigLoaderError[IndexConfigError], RawRorConfig]] = {
    indexJsonContentService
      .sourceOf(indexName.index, Config.auditIndexConst.id)
      .flatMap {
        case Right(source) =>
          source
            .collect { case (key: String, value: String) => (key, value) }
            .find(_._1 == Config.auditIndexConst.settingsKey)
            .map { case (_, rorYamlString) => RawRorConfig.fromString(rorYamlString).map(_.left.map(ParsingError.apply)) }
            .getOrElse(configLoaderError(IndexConfigUnknownStructure))
        case Left(CannotReachContentSource) =>
          configLoaderError(IndexConfigNotExist)
        case Left(ContentNotFound) =>
          configLoaderError(IndexConfigNotExist)
      }
  }

  def save(config: RawRorConfig, rorConfigurationIndex: RorConfigurationIndex): Task[Either[SavingIndexConfigError, Unit]] = {
    indexJsonContentService
      .saveContent(
        rorConfigurationIndex.index,
        Config.auditIndexConst.id,
        Map(Config.auditIndexConst.settingsKey -> config.raw)
      )
      .map {
        _.left.map { case CannotWriteToIndex => SavingIndexConfigError.CannotSaveConfig }
      }
  }

  private def configLoaderError(error: IndexConfigError) = Task.now(Left(SpecializedError[IndexConfigError](error)))
}
