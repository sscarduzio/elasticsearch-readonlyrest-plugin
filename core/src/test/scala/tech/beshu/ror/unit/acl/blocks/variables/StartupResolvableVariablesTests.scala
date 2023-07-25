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
import tech.beshu.ror.accesscontrol.blocks.variables.startup.{StartupResolvableVariableCreator, StartupSingleResolvableVariable}
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariableCreator.{CreationError, createMultiVariableFrom, createSingleVariableFrom}
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import eu.timepit.refined.auto._
import tech.beshu.ror.accesscontrol.blocks.variables.VariableCreationConfig
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariableCreator.CreationError.InvalidVariableDefinition
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler

class StartupResolvableVariablesTests extends AnyWordSpec with MockFactory {

  "An env variable" should {
    "have been resolved" when {
      "system env variable with given name exists" in {
        val variable = createSingleVariable("e_@{env:test}")
          .resolve(mockEnvVarProvider(Map("test" -> Some("x"))))
        variable shouldBe Right("e_x")
      }
      "system env variable with given name and transformation exists" in {
        val variable = createSingleVariable(s"""e_@{env:test}#{replace_all("x","y")}""")
          .resolve(mockEnvVarProvider(Map("test" -> Some("x"))))
        variable shouldBe Right("e_y")
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
      "multivariable with transformation is used" in {
        val variable = createMultiVariable("@explode{env:test-multi}#{to_lowercase}")
          .resolve(mockEnvVarProvider(Map("test-multi" -> Some("X,Y,Z"))))
        variable shouldBe Right(NonEmptyList.of("x", "y", "z"))
      }
      "multivariable is used with some text prefix" in {
        val variable = createMultiVariable("test_@explode{env:test-multi}")
          .resolve(mockEnvVarProvider(Map(
            "test-multi" -> Some("x,y")
          )))
        variable shouldBe Right(NonEmptyList.of("test_x", "test_y"))
      }
      "multivariable with transformation is used with some text prefix" in {
        val variable = createMultiVariable("test_@explode{env:test-multi}#{to_uppercase}")
          .resolve(mockEnvVarProvider(Map(
            "test-multi" -> Some("x,y")
          )))
        variable shouldBe Right(NonEmptyList.of("test_X", "test_Y"))
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
        val result = createSingleVariableFrom("test_@{env:}")
        result shouldBe Left(InvalidVariableDefinition("Empty ENV name passed"))
      }
      "more than one multivariable is used" in {
        val result = createMultiVariableFrom("@explode{env:test-multi1}x@explode{env:test-multi2}")
        result shouldBe Left(CreationError.OnlyOneMultiVariableCanBeUsedInVariableDefinition)
      }
      "multivariable is used in single variable context" in {
        val result = createSingleVariableFrom("@explode{env:test-multi1}")
        result shouldBe Left(CreationError.CannotUserMultiVariableInSingleVariableContext)
      }
      "transformation is not valid" when {
        "empty transformation string" in {
          val result = createSingleVariableFrom(s"@{env:test}#{}")
          result shouldBe Left(InvalidVariableDefinition("Unable to parse transformation string: []. Cause: Expression cannot be empty"))
        }
        "invalid syntax" in {
          val (inputs, outputs) = List[(NonEmptyString, Either[CreationError, StartupSingleResolvableVariable])](
            (
              "@{env:test}#{function(}",
              Left(InvalidVariableDefinition("Unable to parse transformation string: [function(]. Cause: Could not parse expression"))
            ),
            (
              """@{env:test}#{function("a" "b")}""",
              Left(InvalidVariableDefinition("Unable to parse transformation string: [function(\"a\" \"b\")]. Cause: Expected ',' or ')' but was 'b'"))
            ),
            (
              """@{env:test}#{function("a",)}""",
              Left(InvalidVariableDefinition("Unable to parse transformation string: [function(\"a\",)]. Cause: Could not parse expression ')'"))
            ),
          ).unzip

          val results = inputs.map(createSingleVariableFrom)
          results should be(outputs)
        }
        "string does not match any function" in {
          val (inputs, outputs) = List[(NonEmptyString, Either[CreationError, StartupSingleResolvableVariable])](
            (
              "@{env:test}#{bilbo}",
              Left(InvalidVariableDefinition("Unable to compile transformation string: [bilbo]. Cause: No function matching given signature: bilbo()"))
            ),
            (
              s"@{env:test}#{to_uppercase.bilbo}",
              Left(InvalidVariableDefinition("Unable to compile transformation string: [to_uppercase.bilbo]. Cause: No function matching given signature: bilbo()"))
            )
          ).unzip

          val results = inputs.map(createSingleVariableFrom)
          results should be(outputs)
        }
        "incorrect args passed to transformation" when {
          "incorrect args count" in {
            val (inputs, outputs) = List[(NonEmptyString, Either[CreationError, StartupSingleResolvableVariable])](
              (
                """@{env:test}#{to_uppercase("arg")}""",
                Left(InvalidVariableDefinition("Unable to compile transformation string: [to_uppercase(\"arg\")]. Cause: No function matching given signature: to_uppercase(string)"))
              ),
              (
                """@{env:test}#{replace_all("arg")}""",
                Left(InvalidVariableDefinition("Unable to compile transformation string: [replace_all(\"arg\")]. Cause: No function matching given signature: replace_all(string)"))
              )
            ).unzip

            val results = inputs.map(createSingleVariableFrom)
            results should be(outputs)
          }
          "incorrect arg passed" in {
            val (inputs, outputs) = List[(NonEmptyString, Either[CreationError, StartupSingleResolvableVariable])](
              (
                """@{env:test}#{replace_all("[a-z","arg")}""",
                Left(InvalidVariableDefinition("Unable to compile transformation string: [replace_all(\"[a-z\",\"arg\")]. Cause: Incorrect first arg '[a-z'. Cause Unclosed character class near index 3"))
              ),
              (
                s"""@{env:test}#{replace_first("[a-z","arg")}""",
                Left(InvalidVariableDefinition("Unable to compile transformation string: [replace_first(\"[a-z\",\"arg\")]. Cause: Incorrect first arg '[a-z'. Cause Unclosed character class near index 3"))
              )
            ).unzip

            val results = inputs.map(createSingleVariableFrom)
            results should be(outputs)
          }
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

  private implicit val variableCreationConfig: VariableCreationConfig =
    VariableCreationConfig(TransformationCompiler.withoutAliases)
}
