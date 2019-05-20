package tech.beshu.ror.utils

import scala.util.Try

trait EnvVarsProvider {
  def getEnv(name: String): Option[String]
}

object OsEnvVarsProvider extends EnvVarsProvider {
  override def getEnv(name: String): Option[String] =
    Try(Option(System.getenv(name))).toOption.flatten
}
