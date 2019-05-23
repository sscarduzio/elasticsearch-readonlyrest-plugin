package tech.beshu.ror.configuration

import io.circe.yaml.parser
import monix.eval.Task
import tech.beshu.ror.configuration.ConfigLoader.{ConfigLoaderError, RawRorConfig}
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError.{InvalidContent, SpecializedError}
import tech.beshu.ror.configuration.IndexConfigLoader.IndexConfigError
import tech.beshu.ror.configuration.IndexConfigLoader.IndexConfigError.IndexConfigNotExist
import tech.beshu.ror.es.IndexContentProvider

class IndexConfigLoader(indexContentProvider: IndexContentProvider)
  extends ConfigLoader[IndexConfigError] {

  override def load(): Task[Either[ConfigLoaderError[IndexConfigError], RawRorConfig]] =
    indexContentProvider
      .contentOf(".readonlyrest", "settings", "1")
      .map {
        case Right(content) =>
          // todo: get settings key value
          parseIndexContent(content).map(RawRorConfig.apply)
        case Left(IndexContentProvider.CannotReachContentSource) =>
          Left(SpecializedError[IndexConfigError](IndexConfigNotExist))
        case Left(IndexContentProvider.ContentNotFound) =>
          Left(SpecializedError[IndexConfigError](IndexConfigNotExist))
      }

  private def parseIndexContent(content: String) = {
    parser
      .parse(content)
      .left.map(InvalidContent.apply)
      .flatMap { json => validateRorJson(json) }
  }
}

object IndexConfigLoader {

  sealed trait IndexConfigError
  object IndexConfigError {
    case object IndexConfigNotExist extends IndexConfigError
  }

}
