package tech.beshu.ror.acl.blocks.variables.startup

import cats.data.NonEmptyList
import tech.beshu.ror.providers.EnvVarProvider

object StartupMultiResolvableVariableCreator extends BaseStartupResolvableVariableCreator[NonEmptyList[String]] {

  override protected def createEnvVariable(envName: EnvVarProvider.EnvVarName): StartupResolvableVariable[NonEmptyList[String]] =
    StartupMultiResolvableVariable.Env(envName)

  override protected def createTextVariable(value: String): StartupResolvableVariable[NonEmptyList[String]] =
    StartupMultiResolvableVariable.Text(value)

  override protected def createComposedVariable(variables: NonEmptyList[StartupResolvableVariable[NonEmptyList[String]]]): StartupResolvableVariable[NonEmptyList[String]] =
    StartupMultiResolvableVariable.Composed(variables)
}
