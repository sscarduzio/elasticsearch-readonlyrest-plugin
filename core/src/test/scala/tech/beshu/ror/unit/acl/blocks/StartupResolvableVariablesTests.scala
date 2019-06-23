package tech.beshu.ror.unit.acl.blocks

import org.scalamock.scalatest.MockFactory
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.variables.startup.StartupResolvableVariable.ResolvingError
import tech.beshu.ror.acl.blocks.variables.startup.StartupResolvableVariableCreator
import tech.beshu.ror.acl.blocks.variables.startup.StartupResolvableVariableCreator.CreationError
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.utils.TestsUtils.StringOps

class StartupResolvableVariablesTests extends WordSpec with MockFactory {

  "An env variable" should {
    "have been resolved" when {
      "system env variable with given name exists" in {
        val variable = createVariable("e_@{env:test}")
          .resolve(mockEnvVarProvider(Map("test" -> Some("x"))))
        variable shouldBe Right("e_x")
      }
      "old style syntax is used and system env exists" in {
        val variable = createVariable("env:test")
          .resolve(mockEnvVarProvider(Map("test" -> Some("x"))))
        variable shouldBe Right("x")
      }
    }
    "have not been resolved" when {
      "system env variable with given name doesn't exist" in {
        val variable = createVariable("@{env:test}")
          .resolve(mockEnvVarProvider(Map("test" -> None)))
        variable shouldBe Left(ResolvingError("Cannot resolve ENV variable 'test'"))
      }
    }
    "have not been able to be created" when {
      "env name is an empty string" in {
        StartupResolvableVariableCreator.createFrom("test_@{env:}") shouldBe {
          Left(CreationError("Cannot create env variable, because no name of env variable is passed"))
        }
      }
    }
    "be treated as regular string" when {
      "old style syntax is used together with other regular text" in {
        val variable = createVariable("e env:test")
          .resolve(mockEnvVarProvider(Map.empty))
        variable shouldBe Right("e env:test")
      }
      "unknown type of variable is used" in {
        val variable = createVariable("@{user}")
          .resolve(mockEnvVarProvider(Map.empty))
        variable shouldBe Right("@{user}")
      }
    }
  }

  "A deprecated text 'variable'" should {
    "have been resolved as string" in {
      val variable = createVariable("text:test")
        .resolve(mockEnvVarProvider(Map.empty))
      variable shouldBe Right("test")
    }
    "have not been resolved" when {
      "is used together with regular text" in {
        val variable = createVariable("sth text:test")
          .resolve(mockEnvVarProvider(Map.empty))
        variable shouldBe Right("sth text:test")
      }
    }
  }

  private def createVariable(text: String) = {
    StartupResolvableVariableCreator.createFrom(text).right.get
  }

  private def mockEnvVarProvider(envs: Map[String, Option[String]]) = {
    val provider = mock[EnvVarsProvider]
    envs.foreach { case (envName, envValue) =>
      (provider.getEnv _).expects(EnvVarName(envName.nonempty)).returns(envValue)
    }
    provider
  }
}
