package tech.beshu.ror.providers

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName

import scala.util.Try

trait EnvVarsProvider {
  def getEnv(name: EnvVarName): Option[String]
}

object EnvVarProvider {

  final case class EnvVarName(value: NonEmptyString)
}

object OsEnvVarsProvider extends EnvVarsProvider {
  override def getEnv(name: EnvVarName): Option[String] =
    Try(Option(System.getenv(name.value.value))).toOption.flatten
}
