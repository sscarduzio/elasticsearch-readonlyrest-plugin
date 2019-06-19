package tech.beshu.ror.utils

import tech.beshu.ror.acl.domain.EnvVarName

import scala.util.Try

trait EnvVarsProvider {
  def getEnv(name: EnvVarName): Option[String]
}

object OsEnvVarsProvider extends EnvVarsProvider {
  override def getEnv(name: EnvVarName): Option[String] =
    Try(Option(System.getenv(name.value.value))).toOption.flatten
}
