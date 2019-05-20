package tech.beshu.ror.configuration

import io.circe.yaml.parser
import monix.eval.Task
import tech.beshu.ror.configuration.ConfigLoader.{ConfigLoaderError, RawRorConfig}
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError.SpecializedError
import tech.beshu.ror.configuration.IndexConfigLoader.IndexConfigError
import tech.beshu.ror.configuration.IndexConfigLoader.IndexConfigError.{IndexConfigNotExist, InvalidIndexContent}
import tech.beshu.ror.es.IndexContentProvider

class IndexConfigLoader(indexContentProvider: IndexContentProvider)
  extends ConfigLoader[IndexConfigError] {

  override def load(): Task[Either[ConfigLoaderError[IndexConfigError], RawRorConfig]] =
    indexContentProvider
      .contentOf(".readonlyrest", "settings", "1")
      .map {
        case Right(content) =>
          parseIndexContent(content).map(RawRorConfig.apply)
        case Left(IndexContentProvider.Error.CannotReachContentSource) =>
          Left(SpecializedError[IndexConfigError](IndexConfigNotExist))
        case Left(IndexContentProvider.Error.ContentNotFound) =>
          Left(SpecializedError[IndexConfigError](IndexConfigNotExist))
      }

  private def parseIndexContent(content: String) = {
    parser
      .parse(content)
      .left.map(ex => SpecializedError(InvalidIndexContent(ex)))
      .flatMap { json => validateRorJson(json) }
  }
}

object IndexConfigLoader {

  sealed trait IndexConfigError
  object IndexConfigError {
    case object IndexConfigNotExist extends IndexConfigError
    final case class InvalidIndexContent(throwable: Throwable) extends IndexConfigError
  }

}
