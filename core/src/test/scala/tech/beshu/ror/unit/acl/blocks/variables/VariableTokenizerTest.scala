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
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.variables.Tokenizer
import tech.beshu.ror.accesscontrol.blocks.variables.Tokenizer.Token._
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class VariableTokenizerTest extends AnyWordSpec with Matchers {

  "Tokenizer test" when {
    "text used" should {
      "tokenize transformation to text" when {
        "only transformation string" in {
          val text: NonEmptyString = "#{to_lowercase}"
          val result = Tokenizer.tokenize(text)
          result should be(NonEmptyList.of(
            Text("#{to_lowercase}"),
          ))
        }
        "text prepends transformation string" in {
          val text: NonEmptyString = "text#{to_lowercase}"
          val result = Tokenizer.tokenize(text)
          result should be(NonEmptyList.of(
            Text("text#{to_lowercase}"),
          ))
        }
      }
    }
    "placeholder used" when {
      "simple placeholder used" in {
        val text: NonEmptyString = "jwt_value_@{jwt:tech.beshu.mainGroupsString}"
        val result = Tokenizer.tokenize(text)
        result should be(NonEmptyList.of(
          Text("jwt_value_"),
          Placeholder("jwt:tech.beshu.mainGroupsString", "@{jwt:tech.beshu.mainGroupsString}", None)
        ))
      }
      "placeholder with transformation" in {
        val text: NonEmptyString = "jwt_value_@{jwt:tech.beshu.mainGroupsString}#{to_lowercase}"
        val result = Tokenizer.tokenize(text)
        result should be(NonEmptyList.of(
          Text("jwt_value_"),
          Placeholder(
            "jwt:tech.beshu.mainGroupsString",
            "@{jwt:tech.beshu.mainGroupsString}",
            Some(Transformation("to_lowercase", "#{to_lowercase}"))
          )
        ))
      }
      "placeholder with transformation with arg" in {
        val text: NonEmptyString = s"""jwt_value_@{jwt:tech.beshu.mainGroupsString}#{abc("().,\\"")}"""
        val result = Tokenizer.tokenize(text)
        result should be(NonEmptyList.of(
          Text("jwt_value_"),
          Placeholder(
            "jwt:tech.beshu.mainGroupsString",
            "@{jwt:tech.beshu.mainGroupsString}",
            Some(Transformation("abc(\"().,\\\"\")", "#{abc(\"().,\\\"\")}"))
          )
        ))

      }
      "transformation as text when transformation string is not closed with brace" in {
        val text: NonEmptyString = "jwt_value_@{jwt:tech.beshu.mainGroupsString}#{to_lowercase"
        val result = Tokenizer.tokenize(text)
        result should be(NonEmptyList.of(
          Text("jwt_value_"),
          Placeholder(
            "jwt:tech.beshu.mainGroupsString",
            "@{jwt:tech.beshu.mainGroupsString}",
            None
          ),
          Text("#{to_lowercase")
        ))
      }
      "transformation with escaped closing brace" in {
        val text: NonEmptyString = s"""jwt_value_@{jwt:tech.beshu.mainGroupsString}#{replace_all("\\}","x")}"""
        val result = Tokenizer.tokenize(text)
        result should be(NonEmptyList.of(
          Text("jwt_value_"),
          Placeholder(
            "jwt:tech.beshu.mainGroupsString",
            "@{jwt:tech.beshu.mainGroupsString}",
            Some(Transformation("replace_all(\"\\}\",\"x\")", "#{replace_all(\"\\}\",\"x\")}"))

          )
        ))
      }
    }
    "explodable placeholder used" when {
      "simple placeholder" in {
        val text: NonEmptyString = "g@explode{jwt:tech.beshu.mainGroupsString}"
        val result = Tokenizer.tokenize(text)
        result should be(NonEmptyList.of(
          Text("g"),
          ExplodablePlaceholder("jwt:tech.beshu.mainGroupsString", "@explode{jwt:tech.beshu.mainGroupsString}", None)
        ))
      }
      "placeholder with transformation" in {
        val text: NonEmptyString = s"g@explode{jwt:tech.beshu.mainGroupsString}#{to_lowercase}"
        val result = Tokenizer.tokenize(text)
        result should be(NonEmptyList.of(
          Text("g"),
          ExplodablePlaceholder(
            "jwt:tech.beshu.mainGroupsString",
            "@explode{jwt:tech.beshu.mainGroupsString}",
            Some(Transformation("to_lowercase", "#{to_lowercase}"))
          )
        ))
      }
      "transformation as text when transformation string is not closed with brace" in {
        val text: NonEmptyString = s"g@explode{jwt:tech.beshu.mainGroupsString}#{to_lowercase"
        val result = Tokenizer.tokenize(text)
        result should be(NonEmptyList.of(
          Text("g"),
          ExplodablePlaceholder(
            "jwt:tech.beshu.mainGroupsString",
            "@explode{jwt:tech.beshu.mainGroupsString}",
            None
          ),
          Text("#{to_lowercase")
        ))
      }
    }
  }
}
