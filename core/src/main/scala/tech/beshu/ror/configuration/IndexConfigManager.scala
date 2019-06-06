package tech.beshu.ror.configuration

import cats.Show
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.IndexConfigManager.{IndexConfigError, SavingIndexConfigError, auditIndexConsts}
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError.IndexConfigNotExist
import tech.beshu.ror.es.IndexContentManager
import tech.beshu.ror.es.IndexContentManager.{CannotReachContentSource, CannotWriteToIndex, ContentNotFound}
import tech.beshu.ror.utils.LoggerOps._

class IndexConfigManager(indexContentManager: IndexContentManager)
  extends ConfigLoader[IndexConfigError]
    with Logging {

  override def load(): Task[Either[ConfigLoaderError[IndexConfigError], RawRorConfig]] = {
    indexContentManager
      .contentOf(auditIndexConsts.indexName, auditIndexConsts.typeName, auditIndexConsts.id)
      .map {
        case Right(content) =>
          // todo: get settings key value
          RawRorConfig.fromString(content).left.map(ParsingError.apply)
        case Left(CannotReachContentSource) =>
          Left(SpecializedError[IndexConfigError](IndexConfigNotExist))
        case Left(ContentNotFound) =>
          Left(SpecializedError[IndexConfigError](IndexConfigNotExist))
      }
  }

  def save(config: RawRorConfig): Task[Either[SavingIndexConfigError, Unit]] = {
    indexContentManager
      .saveContent(auditIndexConsts.indexName, auditIndexConsts.typeName, auditIndexConsts.id, config.rawConfig.noSpaces)
      .map {
        _.left.map {
          case CannotWriteToIndex(ex) =>
            logger.errorEx("Cannot save config in index", ex)
            SavingIndexConfigError.CannotSaveConfig
        }
      }
  }
}

object IndexConfigManager {

  sealed trait IndexConfigError
  object IndexConfigError {
    case object IndexConfigNotExist extends IndexConfigError

    implicit val show: Show[IndexConfigError] = Show.show {
      case IndexConfigNotExist => "Cannot find config index"
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
  }
}

