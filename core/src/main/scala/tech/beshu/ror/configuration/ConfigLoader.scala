package tech.beshu.ror.configuration

import io.circe.Json
import monix.eval.Task
import tech.beshu.ror.configuration.ConfigLoader.{ConfigLoaderError, RawRorConfig}
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError.{MoreThanOneRorSection, NoRorSection}

trait ConfigLoader[SPECIALIZED_ERROR] {

  def load(): Task[Either[ConfigLoaderError[SPECIALIZED_ERROR], RawRorConfig]]

  protected def validateRorJson(json: Json): Either[ConfigLoaderError[SPECIALIZED_ERROR], Json] = {
    json \\ "readonlyrest" match {
      case Nil => Left(NoRorSection)
      case one :: Nil => Right(one)
      case _ => Left(MoreThanOneRorSection)
    }
  }
}

object ConfigLoader {

  final case class RawRorConfig(rawConfig: Json) extends AnyVal

  sealed trait ConfigLoaderError[+SPECIALIZED_ERROR]
  object ConfigLoaderError {
    case object NoRorSection extends ConfigLoaderError[Nothing]
    case object MoreThanOneRorSection extends ConfigLoaderError[Nothing]
    final case class InvalidContent(throwable: Throwable) extends ConfigLoaderError[Nothing]
    final case class SpecializedError[ERROR](error: ERROR) extends ConfigLoaderError[ERROR]
  }
}