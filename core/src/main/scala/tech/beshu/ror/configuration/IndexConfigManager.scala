package tech.beshu.ror.configuration

import cats.Show
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.IndexConfigManager.{IndexConfigError, SavingIndexConfigError, auditIndexConsts}
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError.{IndexConfigNotExist, IndexConfigUnknownStructure}
import tech.beshu.ror.es.IndexJsonContentManager
import tech.beshu.ror.es.IndexJsonContentManager.{CannotReachContentSource, CannotWriteToIndex, ContentNotFound}
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.YamlOps

import scala.collection.JavaConverters._

class IndexConfigManager(indexContentManager: IndexJsonContentManager)
  extends ConfigLoader[IndexConfigError]
    with Logging {

  override def load(): Task[Either[ConfigLoaderError[IndexConfigError], RawRorConfig]] = {
    indexContentManager
      .sourceOf(auditIndexConsts.indexName, auditIndexConsts.typeName, auditIndexConsts.id)
      .map {
        case Right(source) =>
          source.asScala
            .collect { case (key: String, value: String) => (key, value) }.toMap
            .find(_._1 == auditIndexConsts.settingsKey)
            .map { case (_, rorYamlString) => RawRorConfig.fromString(rorYamlString).left.map(ParsingError.apply) }
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
        auditIndexConsts.indexName,
        auditIndexConsts.typeName,
        auditIndexConsts.id,
        Map(auditIndexConsts.settingsKey -> YamlOps.jsonToYamlString(config.rawConfig)).asJava
      )
      .map {
        _.left.map {
          case CannotWriteToIndex(ex) =>
            logger.errorEx("Cannot save config in index", ex)
            SavingIndexConfigError.CannotSaveConfig
        }
      }
  }

  private def configLoaderError(error: IndexConfigError) = Left(SpecializedError[IndexConfigError](error))
}

object IndexConfigManager {

  sealed trait IndexConfigError
  object IndexConfigError {
    case object IndexConfigNotExist extends IndexConfigError
    case object IndexConfigUnknownStructure extends IndexConfigError

    implicit val show: Show[IndexConfigError] = Show.show {
      case IndexConfigNotExist => "Cannot find config index"
      case IndexConfigUnknownStructure => s"Unknown structure of index config"
    }

    val indexConfigLoaderErrorShow: Show[ConfigLoaderError[IndexConfigError]] =
      ConfigLoaderError.show[IndexConfigError]
  }

  sealed trait SavingIndexConfigError
  object SavingIndexConfigError {
    case object CannotSaveConfig extends SavingIndexConfigError

    implicit val show: Show[SavingIndexConfigError] = Show.show {
      case CannotSaveConfig => "Cannot save config to index"
    }
  }

  private object auditIndexConsts {
    val indexName = ".readonlyrest"
    val typeName = "settings"
    val id = "1"
    val settingsKey = "settings"
  }
}

