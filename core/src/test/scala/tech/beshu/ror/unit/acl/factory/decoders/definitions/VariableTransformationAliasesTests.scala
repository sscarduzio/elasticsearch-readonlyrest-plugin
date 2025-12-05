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
package tech.beshu.ror.unit.acl.factory.decoders.definitions
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.SupportedVariablesFunctions
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.Function.{FunctionChain, ReplaceFirst}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.FunctionName
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.VariableTransformationAliasesDefinitionsDecoder
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class VariableTransformationAliasesTests
  extends BaseDecoderTest(VariableTransformationAliasesDefinitionsDecoder.create(SupportedVariablesFunctions.default)) {

  "A variable transformation aliases definition" should {
    "be able to be loaded from settings" when {
      "one alias is defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |variables_function_aliases:
               | - strip_group_prefix: replace_first("^group", "")
             """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val alias = definitions.items.head.alias
            alias.name should be(FunctionName("strip_group_prefix"))
            alias.value shouldBe a[ReplaceFirst]
          }
        )
      }
      "two aliases are defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |variables_function_aliases:
               | - strip_group_prefix: replace_first("^group", "")
               | - strip_group_suffix: replace_first("group$$", "")
             """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 2
            val alias1 = definitions.items.head.alias
            alias1.name should be(FunctionName("strip_group_prefix"))
            alias1.value shouldBe a[ReplaceFirst]

            val alias2 = definitions.items(1).alias
            alias2.name should be(FunctionName("strip_group_suffix"))
            alias2.value shouldBe a[ReplaceFirst]
          }
        )
      }
      "alias with function call chain is defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |variables_function_aliases:
               | - normalize: replace_first("^group_", "").replace_all("x", "y").to_lowercase
             """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val alias = definitions.items.head.alias
            alias.name should be(FunctionName("normalize"))
            alias.value shouldBe a[FunctionChain]

            val (inputs, outputs) = List(
              ("group_ABC_x", "abc_y"),
              ("GROUP_ABC_x", "group_abc_y"),
              ("group_XXX", "xxx")
            ).unzip

            inputs.map(alias.value.apply) should be(outputs)
          }
        )
      }
    }
  }
  "be empty" when {
    "there is no aliases section" in {
      assertDecodingSuccess(
        yaml = "",
        assertion = { definitions =>
          definitions.items should have size 0
        }
      )
    }
  }
  "not be able to be loaded from settings" when {
    "aliases section is empty" in {
      assertDecodingFailure(
        yaml =
          s"""
             |variables_function_aliases:
       """.stripMargin,
        assertion = { error =>
          error should be(DefinitionsLevelCreationError(Message(
            "variables_function_aliases declared, but no definition found"
          )))
        }
      )
    }
    "alias definition is malformed" when {
      "unknown function used in alias" in {
        assertDecodingFailure(
          yaml =
            s"""
               |variables_function_aliases:
               | - alias_name: to_snakecase
             """.stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message(
              "variables_function_aliases definition malformed: Unable to compile transformation for alias 'alias_name'. " +
                "Cause: No function with name 'to_snakecase'. Supported functions are: [replace_all, replace_first, to_lowercase, to_uppercase]"
            )))
          }
        )
      }
      "incorrect argument passed to function" in {
        assertDecodingFailure(
          yaml =
            s"""
               |variables_function_aliases:
               | - alias_name: replace_first("([a-z", "replacement")
             """.stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message(
              "variables_function_aliases definition malformed: Unable to compile transformation for alias 'alias_name'. " +
                "Cause: Error for function 'replace_first': Incorrect first arg '([a-z'. Cause: Unclosed character class near index 4"
            )))
          }
        )
      }
      "incorrect args count in function call" in {
        assertDecodingFailure(
          yaml =
            s"""
               |variables_function_aliases:
               | - alias_name: replace_first("xyz")
             """.stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message(
              "variables_function_aliases definition malformed: Unable to compile transformation for alias 'alias_name'. " +
                "Cause: Error for function 'replace_first': Incorrect function arguments count. Expected: 2, actual: 1."
            )))
          }
        )
      }
      "alias tried to be applied in aliases definition" in {
        assertDecodingFailure(
          yaml =
            s"""
               |variables_function_aliases:
               | - alias_name: func(some_alias)
             """.stripMargin,
          assertion = { error =>
            error should be(DefinitionsLevelCreationError(Message(
              "variables_function_aliases definition malformed: Unable to compile transformation for alias 'alias_name'. " +
                "Cause: Function aliases cannot be applied in this context"
            )))
          }
        )
      }
    }
  }
}
