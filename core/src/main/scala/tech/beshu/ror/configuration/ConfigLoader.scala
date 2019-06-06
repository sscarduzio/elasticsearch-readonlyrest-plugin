package tech.beshu.ror.configuration

import cats.Show
import monix.eval.Task
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.RawRorConfig.ParsingRorConfigError

trait ConfigLoader[SPECIALIZED_ERROR] {

  def load(): Task[Either[ConfigLoaderError[SPECIALIZED_ERROR], RawRorConfig]]

}

object ConfigLoader {

  sealed trait ConfigLoaderError[+SPECIALIZED_ERROR]
  object ConfigLoaderError {
    final case class ParsingError(error: ParsingRorConfigError) extends ConfigLoaderError[Nothing]
    final case class SpecializedError[ERROR](error: ERROR) extends ConfigLoaderError[ERROR]

    implicit def show[E: Show]: Show[ConfigLoaderError[E]] = Show.show {
      case ParsingError(error) => RawRorConfig.ParsingRorConfigError.show.show(error)
      case SpecializedError(error) => implicitly[Show[E]].show(error)
    }
  }

}