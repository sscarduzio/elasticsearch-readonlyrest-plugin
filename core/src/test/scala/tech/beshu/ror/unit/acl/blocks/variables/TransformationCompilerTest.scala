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

import eu.timepit.refined.auto.*
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler.CompilationError.{UnableToCompileTransformation, UnableToParseTransformation}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.{Function, FunctionAlias, FunctionName}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class TransformationCompilerTest extends AnyWordSpec with Matchers with Inside {

  private val alias = FunctionAlias(
    FunctionName("strip_group_prefix"),
    new Function.ReplaceFirst("^group".r, "")
  )
  private val compiler = TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq(alias))

  "Variable transformation compiler" should {
    "return compiled function" when {
      "single function used" when {
        "to_uppercase" in {
          val result = compiler.compile("to_uppercase")
          inside(result) {
            case Right(_: Function.ToUpperCase) =>
          }
        }
        "to_lowercase" in {
          val result = compiler.compile("to_lowercase")
          inside(result) {
            case Right(_: Function.ToLowerCase) =>
          }
        }
        "replace_first" in {
          val result = compiler.compile("""replace_first("group","class")""")
          inside(result) {
            case Right(f: Function.ReplaceFirst) =>
              val inputsAndOutputs = List(
                ("group1", "class1"),
                ("group1_group2", "class1_group2")
              )
              functionTest(f, inputsAndOutputs)
          }
        }
        "replace_all" in {
          val result = compiler.compile("""replace_all("group","class")""")
          inside(result) {
            case Right(f: Function.ReplaceAll) =>
              val inputsAndOutputs = List(
                ("group1", "class1"),
                ("group1_group2", "class1_class2")
              )
              functionTest(f, inputsAndOutputs)
          }
        }
        "function alias used" in {
          val result = compiler.compile("func(strip_group_prefix)")
          inside(result) {
            case Right(f) =>
              f should be(alias.value)
              val inputsAndOutputs = List(
                ("group1", "1"),
                ("group1_group2", "1_group2"),
                ("_group1", "_group1")
              )
              functionTest(f, inputsAndOutputs)
          }
        }
      }
      "function chain used" in {
        val result = compiler.compile("""to_uppercase.replace_first("^CLASS", "GROUP").to_lowercase.replace_all("group", "SET")""")
        inside(result) {
          case Right(f: Function.FunctionChain) =>
            val inputsAndOutputs = List(
              ("class_x", "SET_x"),
              ("class_x_class_y", "SET_x_class_y"),
              ("aclass_x_class_y", "aclass_x_class_y")
            )
            functionTest(f, inputsAndOutputs)
        }
      }
      "function without args called with parentheses" in {
        val result = compiler.compile("""to_uppercase()""")
        inside(result) {
          case Right(_: Function.ToUpperCase) =>
        }
      }
    }
    "return compilation error" when {
      "less args passed than needed" in {
        val result = compiler.compile("replace_first(\"group\")")
        inside(result) {
          case Left(message) =>
            message should be(UnableToCompileTransformation(
              "Error for function 'replace_first': Incorrect function arguments count. Expected: 2, actual: 1."
            ))
        }
      }
      "more args passed than needed" in {
        val result = compiler.compile("""replace_first(a,b,c)""")
        inside(result) {
          case Left(message) =>
            message should be(UnableToCompileTransformation(
              "Error for function 'replace_first': Incorrect function arguments count. Expected: 2, actual: 3."
            ))
        }
      }
      "no such function" in {
        val result = compiler.compile("replace_last(a,b)")
        inside(result) {
          case Left(message) =>
            message should be(UnableToCompileTransformation(
              "No function with name 'replace_last'. Supported functions are: [replace_all, replace_first, to_lowercase, to_uppercase]"
            ))
        }
      }
      "no such alias" in {
        val result = compiler.compile("func(some_alias)")
        inside(result) {
          case Left(message) =>
            message should be(UnableToCompileTransformation("Alias with name 'some_alias' does not exits."))
        }
      }
      "alias used when no alias allowed" in {
        val compiler = TransformationCompiler.withoutAliases(SupportedVariablesFunctions.default)
        val result = compiler.compile("func(some_alias)")
        inside(result) {
          case Left(message) =>
            message should be(UnableToCompileTransformation("Function aliases cannot be applied in this context"))
        }
      }
    }
    "return parsing error" when {
      "unclosed function brace" in {
        val result = compiler.compile("function(")
        inside(result) {
          case Left(message) =>
            message should be(UnableToParseTransformation("Could not parse expression"))
        }
      }
      "function missing comma" in {
        val result = compiler.compile("function(\"a\" \"b\")")
        inside(result) {
          case Left(message) =>
            message should be(UnableToParseTransformation("Expected ',' or ')' but was 'b'"))
        }
      }
      "function missing arg after comma" in {
        val result = compiler.compile("function(\"a\",)")
        inside(result) {
          case Left(message) =>
            message should be(UnableToParseTransformation("Could not parse expression ')'"))
        }
      }
    }
  }

  // if any of the following tests fail that means the backward compatibility was broken
  "Transforming functions test" when {
    "replace first" in {
      functionTest(
        new Function.ReplaceFirst("l".r, "X"),
        "Hello World",
        "HeXlo World"
      )
      functionTest(
        new Function.ReplaceFirst("awesome".r, "great"),
        "Scala is awesome",
        "Scala is great"
      )
      functionTest(
        new Function.ReplaceFirst("12345".r, "67890"),
        "12345 12345 12345",
        "67890 12345 12345"
      )
      functionTest(
        new Function.ReplaceFirst("abc".r, "XYZ"),
        "abcABCabcABC",
        "XYZABCabcABC"
      )
      functionTest(
        new Function.ReplaceFirst("1".r, "one"),
        "Testing 1 2 3",
        "Testing one 2 3"
      )
      functionTest(
        new Function.ReplaceFirst(" ".r, "_"),
        " Spaces ",
        "_Spaces "
      )
      functionTest(
        new Function.ReplaceFirst("Java".r, "Kotlin"),
        "Scala is fun",
        "Scala is fun"
      )
      functionTest(
        new Function.ReplaceFirst("!@#\\$".r, "****"),
        "Special characters: !@#$",
        "Special characters: ****"
      )
      functionTest(
        new Function.ReplaceFirst("pattern".r, "replacement"),
        "",
        ""
      )
      functionTest(
        new Function.ReplaceFirst("(?i)scala".r, "Scala"), // case insensitive
        "Scala is scalaSCALA SCALA",
        "Scala is scalaSCALA SCALA"
      )
      functionTest(
        new Function.ReplaceFirst("\\d+".r, "NUM"), // sequence of digits
        "12345 123 123456789",
        "NUM 123 123456789"
      )
      functionTest(
        new Function.ReplaceFirst("[A-C]+".r, "X"), // sequence of A,B or C
        "abcABCabcABC",
        "abcXabcABC"
      )
      functionTest(
        new Function.ReplaceFirst("\\d".r, "NUM"),
        "Testing 1 2 3",
        "Testing NUM 2 3"
      )
      functionTest(
        new Function.ReplaceFirst("\\s+".r, "_"), // whitespace characters
        " Spaces ",
        "_Spaces "
      )
      functionTest(
        new Function.ReplaceFirst("Java|Kotlin".r, "Scala"),
        "Java is fun",
        "Scala is fun"
      )
      functionTest(
        new Function.ReplaceFirst("[!@#$%^&]".r, "_"), // all special characters
        "Special characters: !@#$%^&",
        "Special characters: _@#$%^&"
      )
    }
    "replace all" in {
      functionTest(
        new Function.ReplaceAll("l".r, "X"),
        "Hello World",
        "HeXXo WorXd"
      )
      functionTest(
        new Function.ReplaceAll("awesome".r, "great"),
        "Scala is awesome",
        "Scala is great"
      )
      functionTest(
        new Function.ReplaceAll("12345".r, "67890"),
        "12345 12345 12345",
        "67890 67890 67890"
      )
      functionTest(
        new Function.ReplaceAll("abc".r, "XYZ"),
        "abcABCabcABC",
        "XYZABCXYZABC"
      )
      functionTest(
        new Function.ReplaceAll("1".r, "one"),
        "Testing 1 2 3",
        "Testing one 2 3"
      )
      functionTest(
        new Function.ReplaceAll(" ".r, "_"),
        " Spaces ",
        "_Spaces_"
      )
      functionTest(
        new Function.ReplaceAll("Java".r, "Kotlin"),
        "Scala is fun",
        "Scala is fun"
      )
      functionTest(
        new Function.ReplaceAll("!@#\\$".r, "****"),
        "Special characters: !@#$",
        "Special characters: ****"
      )
      functionTest(
        new Function.ReplaceAll("pattern".r, "replacement"),
        "",
        ""
      )
      functionTest(
        new Function.ReplaceAll("(?i)scala".r, "Scala"), // case insensitive
        "Scala is scalaSCALA SCALA",
        "Scala is ScalaScala Scala"
      )
      functionTest(
        new Function.ReplaceAll("\\d+".r, "NUM"), // sequence of digits
        "12345 123 123456789",
        "NUM NUM NUM"
      )
      functionTest(
        new Function.ReplaceAll("[A-C]+".r, "X"), // sequence of A,B or C
        "abcABCabcABC",
        "abcXabcX"
      )
      functionTest(
        new Function.ReplaceAll("\\d".r, "NUM"),
        "Testing 1 2 3",
        "Testing NUM NUM NUM"
      )
      functionTest(
        new Function.ReplaceAll("\\s+".r, ""), // whitespace characters
        " Spaces ",
        "Spaces"
      )
      functionTest(
        new Function.ReplaceAll("Java|Kotlin".r, "Scala"),
        "Java is fun",
        "Scala is fun"
      )
      functionTest(
        new Function.ReplaceAll("[!@#$%^&]".r, "_"), // all special characters
        "Special characters: !@#$%^&",
        "Special characters: _______"
      )
    }
    "to lowercase" in {
      val inputsAndOutputs = List(
        ("Hello World", "hello world"),
        ("Scala", "scala"),
        ("12345", "12345"),
        ("abcDEF", "abcdef"),
        ("LoWeRcAsE", "lowercase"),
        ("Testing 1 2 3", "testing 1 2 3"),
        (" Spaces ", " spaces "),
        ("ÇÄÜ", "çäü"),
        ("", ""),
      )
      functionTest(new Function.ToLowerCase, inputsAndOutputs)
    }
    "to uppercase" in {
      val inputsAndOutputs = List(
        ("Hello World", "HELLO WORLD"),
        ("Scala", "SCALA"),
        ("12345", "12345"),
        ("abcDEF", "ABCDEF"),
        ("LoWeRcAsE", "LOWERCASE"),
        ("Testing 1 2 3", "TESTING 1 2 3"),
        (" Spaces ", " SPACES "),
        ("çäü", "ÇÄÜ"),
        ("", "")
      )
      functionTest(new Function.ToUpperCase, inputsAndOutputs)
    }
  }

  private def functionTest(function: Function, input: String, output: String) = {
    function.apply(input) should be(output)
  }

  private def functionTest(function: Function, inputsAndOutputs: List[(String, String)]): Any = {
    val (inputs, outputs) = inputsAndOutputs.unzip
    inputs.map(function.apply) should be(outputs)
  }
}
