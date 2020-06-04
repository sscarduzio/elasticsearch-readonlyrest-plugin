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

import cats.Show
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError.{IndexConfigNotExist, IndexConfigUnknownStructure}
import tech.beshu.ror.configuration.IndexConfigManager.{IndexConfigError, SavingIndexConfigError, auditIndexConst}
import tech.beshu.ror.configuration.loader.ConfigLoader
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.es.IndexJsonContentService.{CannotReachContentSource, CannotWriteToIndex, ContentNotFound}

import scala.collection.JavaConverters._

class IndexConfigManager(indexContentManager: IndexJsonContentService,
                         val rorIndexNameConfiguration: RorIndexNameConfiguration)
  extends ConfigLoader[IndexConfigError]
    with Logging {

  override def load(): Task[Either[ConfigLoaderError[IndexConfigError], RawRorConfig]] = {
    indexContentManager
      .sourceOf(rorIndexNameConfiguration.name, auditIndexConst.id)
      .flatMap {
        case Right(source) =>
          source.asScala
            .collect { case (key: String, value: String) => (key, value) }.toMap
            .find(_._1 == auditIndexConst.settingsKey)
            .map { case (_, rorYamlString) => RawRorConfig.fromString(rorYamlString).map(_.left.map(ParsingError.apply)) }
            .getOrElse(configLoaderError(IndexConfigUnknownStructure))
        case Left(CannotReachContentSource) =>
          configLoaderError(IndexConfigNotExist)
        case Left(ContentNotFound) =>
          configLoaderError(IndexConfigNotExist)
      }
  }

  def save(config: RawRorConfig): Task[Either[SavingIndexConfigError, Unit]] = {
    indexContentManager
      .saveContent(
        rorIndexNameConfiguration.name,
        auditIndexConst.id,
        Map(auditIndexConst.settingsKey -> config.raw).asJava
      )
      .map {
        _.left.map { case CannotWriteToIndex => SavingIndexConfigError.CannotSaveConfig }
      }
  }

  private def configLoaderError(error: IndexConfigError) = Task.now(Left(SpecializedError[IndexConfigError](error)))
}

object IndexConfigManager {

  sealed trait IndexConfigError
  object IndexConfigError {
    case object IndexConfigNotExist extends IndexConfigError
    case object IndexConfigUnknownStructure extends IndexConfigError

    implicit val show: Show[IndexConfigError] = Show.show {
      case IndexConfigNotExist => "Cannot find settings index"
      case IndexConfigUnknownStructure => s"Unknown structure of index settings"
    }

    val indexConfigLoaderErrorShow: Show[ConfigLoaderError[IndexConfigError]] =
      ConfigLoaderError.show[IndexConfigError]
  }

  sealed trait SavingIndexConfigError
  object SavingIndexConfigError {
    case object CannotSaveConfig extends SavingIndexConfigError

    implicit val show: Show[SavingIndexConfigError] = Show.show {
      case CannotSaveConfig => "Cannot save settings in index"
    }
  }

  private object auditIndexConst {
    val id = "1"
    val settingsKey = "settings"
  }
}

