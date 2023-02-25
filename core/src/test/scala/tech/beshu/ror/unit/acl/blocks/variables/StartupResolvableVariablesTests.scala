/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.unit.acl.blocks.variables

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariable.ResolvingError
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariableCreator.{CreationError, createMultiVariableFrom, createSingleVariableFrom}
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import eu.timepit.refined.auto._

class StartupResolvableVariablesTests extends AnyWordSpec with MockFactory {

  "An env variable" should {
    "have been resolved" when {
      "system env variable with given name exists" in {
        val variable = createSingleVariable("e_@{env:test}")
          .resolve(mockEnvVarProvider(Map("test" -> Some("x"))))
        variable shouldBe Right("e_x")
      }
      "old style syntax is used and system env exists" in {
        val variable = createSingleVariable("env:test")
          .resolve(mockEnvVarProvider(Map("test" -> Some("x"))))
        variable shouldBe Right("x")
      }
      "multivariable is used" in {
        val variable = createMultiVariable("@explode{env:test-multi}")
          .resolve(mockEnvVarProvider(Map("test-multi" -> Some("x,y,z"))))
        variable shouldBe Right(NonEmptyList.of("x", "y", "z"))
      }
      "multivariable is used with some text prefix" in {
        val variable = createMultiVariable("test_@explode{env:test-multi}")
          .resolve(mockEnvVarProvider(Map(
            "test-multi" -> Some("x,y")
          )))
        variable shouldBe Right(NonEmptyList.of("test_x", "test_y"))
      }
    }
    "have not been resolved" when {
      "system env variable with given name doesn't exist" in {
        val variable = createSingleVariable("@{env:test}")
          .resolve(mockEnvVarProvider(Map("test" -> None)))
        variable shouldBe Left(ResolvingError("Cannot resolve ENV variable 'test'"))
      }
    }
    "have not been able to be created" when {
      "env name is an empty string" in {
        createSingleVariableFrom("test_@{env:}") shouldBe {
          Left(CreationError.InvalidVariableDefinition("Empty ENV name passed"))
        }
      }
      "more than one multivariable is used" in {
        createMultiVariableFrom("@explode{env:test-multi1}x@explode{env:test-multi2}") shouldBe {
          Left(CreationError.OnlyOneMultiVariableCanBeUsedInVariableDefinition)
        }
      }
      "multivariable is used in single variable context" in {
        createSingleVariableFrom("@explode{env:test-multi1}") shouldBe {
          Left(CreationError.CannotUserMultiVariableInSingleVariableContext)
        }
      }
    }
    "be treated as regular string" when {
      "old style syntax is used together with other regular text" in {
        val variable = createSingleVariable("e env:test")
          .resolve(mockEnvVarProvider(Map.empty))
        variable shouldBe Right("e env:test")
      }
      "unknown type of variable is used" in {
        val variable = createSingleVariable("@{user}")
          .resolve(mockEnvVarProvider(Map.empty))
        variable shouldBe Right("@{user}")
      }
    }
  }

  "A deprecated text 'variable'" should {
    "have been resolved as string" in {
      val variable = createSingleVariable("text:test")
        .resolve(mockEnvVarProvider(Map.empty))
      variable shouldBe Right("test")
    }
    "have not been resolved" when {
      "is used together with regular text" in {
        val variable = createSingleVariable("sth text:test")
          .resolve(mockEnvVarProvider(Map.empty))
        variable shouldBe Right("sth text:test")
      }
    }
  }

  private def createSingleVariable(text: String) = {
    createSingleVariableFrom(NonEmptyString.unsafeFrom(text)).toOption.get
  }

  private def createMultiVariable(text: String) = {
    StartupResolvableVariableCreator.createMultiVariableFrom(NonEmptyString.unsafeFrom(text)).toOption.get
  }

  private def mockEnvVarProvider(envs: Map[String, Option[String]]) = {
    val provider = mock[EnvVarsProvider]
    envs.foreach { case (envName, envValue) =>
      (provider.getEnv _).expects(EnvVarName(NonEmptyString.unsafeFrom(envName))).returns(envValue)
    }
    provider
  }
}
