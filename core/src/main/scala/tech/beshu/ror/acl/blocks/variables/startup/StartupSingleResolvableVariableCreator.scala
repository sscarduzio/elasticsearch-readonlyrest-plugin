package tech.beshu.ror.acl.blocks.variables.startup

import cats.data.NonEmptyList
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName

object StartupSingleResolvableVariableCreator extends BaseStartupResolvableVariableCreator[String] {

  override protected def createEnvVariable(envName: EnvVarName): StartupResolvableVariable[String] =
    StartupSingleResolvableVariable.Env(envName)

  override protected def createTextVariable(value: String): StartupResolvableVariable[String] =
    StartupSingleResolvableVariable.Text(value)

  override protected def createComposedVariable(variables: NonEmptyList[StartupResolvableVariable[String]]): StartupResolvableVariable[String] =
    StartupSingleResolvableVariable.Composed(variables)
}
