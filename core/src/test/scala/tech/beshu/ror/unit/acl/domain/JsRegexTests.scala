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

import eu.timepit.refined.auto.*
import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.JsRegex
import tech.beshu.ror.utils.TestsUtils.unsafeNes
import tech.beshu.ror.utils.js.{JsCompiler, MozillaJsCompiler}

class JsRegexTests extends AnyWordSpec with Matchers with EitherValues {

  private implicit val jsCompile: JsCompiler = MozillaJsCompiler

  "A JsRegex" should {
    "compile valid regex" in {
      JsRegex.compile("/^abc$/").value.value.value should be ("/^abc$/")
    }
    "not compile" when {
      "passed value is not a regex" in {
        JsRegex.compile("^abc$") should be (Left(JsRegex.CompilationResult.NotRegex))
      }
      "there is a syntax error" in {
        JsRegex.compile("/^b[c$/") should be (Left(JsRegex.CompilationResult.SyntaxError))
      }
      "JS injection was detected (1)" in {
        JsRegex.compile("/^a$/');1+1;console.log('injection');new RegExp('/^a$/") should be (Left(JsRegex.CompilationResult.SyntaxError))
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
        ) should be (Left(JsRegex.CompilationResult.SyntaxError))
      }
    }
  }
}
