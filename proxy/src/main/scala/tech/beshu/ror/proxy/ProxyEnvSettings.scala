package tech.beshu.ror.proxy

import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.domain.{PlainTextSecret, User}
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider

object ProxyEnvSettings {

  def rorSuperuserName(implicit provider: EnvVarsProvider): Option[User.Id] = {
    provider
      .getEnv(EnvVarName("ROR_SUPERUSER_NAME"))
      .flatMap(NonEmptyString.from(_).toOption)
      .map(User.Id.apply)
  }

  def rorSuperUserSecret(implicit provider: EnvVarsProvider): Option[PlainTextSecret] = {
    provider
      .getEnv(EnvVarName("ROR_SUPERUSER_SECRET"))
      .flatMap(NonEmptyString.from(_).toOption)
      .map(PlainTextSecret.apply)
  }
}
