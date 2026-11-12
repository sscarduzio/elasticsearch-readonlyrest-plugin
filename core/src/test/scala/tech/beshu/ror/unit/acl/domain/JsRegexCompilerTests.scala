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
package tech.beshu.ror.unit.acl.domain

import eu.timepit.refined.types.string.NonEmptyString
import org.mozilla.javascript.{Context, ScriptableObject}
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.JsRegex
import tech.beshu.ror.utils.TestsUtils.unsafeNes
import tech.beshu.ror.utils.js.{JsRegexCompiler, MozillaJsRegexCompiler}

import scala.util.Using

class JsRegexCompilerTests extends AnyWordSpec with Matchers with EitherValues {

  private implicit val jsRegexCompiler: JsRegexCompiler = MozillaJsRegexCompiler

  "A JsRegex" should {
    "compile valid regex" in {
      JsRegex.compile("/^abc$/").value.value.value should be("/^abc$/")
    }
    "not compile" when {
      "passed value is not a regex" in {
        JsRegex.compile("^abc$") should be(Left(JsRegex.CompilationResult.NotRegex))
      }
      "there is a syntax error" in {
        JsRegex.compile("/^b[c$/") should be(
          Left(JsRegex.CompilationResult.SyntaxError("SyntaxError: Unterminated character class"))
        )
      }
      "JS injection was detected (1)" in {
        JsRegex.compile("/^a$/');1+1;console.log('injection');new RegExp('/^a$/") should be(
          Left(JsRegex.CompilationResult.SyntaxError("SyntaxError: unmatched ) in regular expression."))
        )
      }
      "JS injection was detected (2)" in {
        JsRegex.compile(
          NonEmptyString.unsafeFrom {
            s"""/^a$$//')
               |1+1
               |console.log('injection')
               |new RegExp('/^a$$//"
               |"""".stripMargin
          }
        ) should be(Left(JsRegex.CompilationResult.SyntaxError("the regex contains a new line")))
      }
      "the regex tries to run Java code" in {
        JsRegex.compile(
          "/x'),Packages.java.lang.System.setProperty('ror.js.regex.test','executed'),(' /"
        ) should be(Left(JsRegex.CompilationResult.SyntaxError("SyntaxError: unmatched ) in regular expression.")))
        System.getProperty("ror.js.regex.test") should be(null)
      }
      "an escaped backslash comes before a parenthesis" in {
        JsRegex.compile("""/\\(/""") should be(
          Left(JsRegex.CompilationResult.SyntaxError("SyntaxError: Unterminated parenthetical"))
        )
      }
    }
    "not run the regex as JS code" when {
      "the regex is valid and contains JS code" in {
        JsRegex
          .compile(
            "/Packages.java.lang.System.setProperty('ror.js.regex.test2','executed')/"
          )
          .isRight should be(true)
        System.getProperty("ror.js.regex.test2") should be(null)
      }
    }
    "compile a regex" when {
      "the regex contains a quote or a backslash" in {
        JsRegex.compile("""/^it's \d+$/""").isRight should be(true)
      }
      "the regex ends with an escaped backslash" in {
        JsRegex.compile("""/a\\/""").isRight should be(true)
      }
      "the regex contains `);`" in {
        JsRegex.compile("/(a);b/").isRight should be(true)
      }
    }
  }

  "A MozillaJsRegexCompiler" should {
    "create a scope without access to Java classes" in {
      Using.resource(Context.enter()) { context =>
        val scope = MozillaJsRegexCompiler.createSafeScope(context)
        List("Packages", "java", "javax", "JavaAdapter", "getClass").foreach { name =>
          withClue(s"[$name]") {
            ScriptableObject.hasProperty(scope, name) should be(false)
          }
        }
      }
    }
  }

}
