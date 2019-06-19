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
package tech.beshu.ror.unit.acl.blocks

import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.RequestContextInitiatedBlockContext.fromRequestContext
import tech.beshu.ror.acl.blocks.values.Variable.Unresolvable.CannotExtractValue
import tech.beshu.ror.acl.blocks.values.VariableCreator
import tech.beshu.ror.acl.blocks.values.VariableCreator.CreationError
import tech.beshu.ror.acl.domain.{LoggedUser, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.utils.TestsUtils._

class VariablesTests extends WordSpec with MockFactory {

  implicit val provider: EnvVarsProvider = OsEnvVarsProvider

  "A header variable" should {
    "have been resolved" when {
      "given variable has corresponding header in request context" in {
        val variable = createVariable("@{key1}")
          .resolve(
            MockRequestContext(headers = Set(headerFrom("key1" -> "x"))),
            mock[BlockContext]
          )
        variable shouldBe Right("x")
      }
      "given variable has corresponding header in request context but upper-case" in {
        val variable = createVariable("h:@{key1}")
          .resolve(
            MockRequestContext(headers = Set(headerFrom("KEY1" -> "x"))),
            mock[BlockContext]
          )
        variable shouldBe Right("h:x")
      }
      "given variable has corresponding header in request context and is defined with new format" in {
        val variable = createVariable("@{header:key1}_ok")
          .resolve(
            MockRequestContext(headers = Set(headerFrom("key1" -> "x"))),
            mock[BlockContext]
          )
        variable shouldBe Right("x_ok")
      }
    }
    "have not been resolved" when {
      "given variable doesn't have corresponding header in request context" in {
        val variable = createVariable("@{key1}")
          .resolve(
            MockRequestContext(headers = Set(headerFrom("key2" -> "x"))),
            mock[BlockContext]
          )
        variable shouldBe Left(CannotExtractValue("Cannot extract user header 'key1' from request context"))
      }
    }
    "have not been able to be created" when {
      "header name is empty string" in {
        VariableCreator.createFrom("h:@{header:}", Right.apply) shouldBe Left(CreationError("No header name passed"))
      }
    }
  }

  "A user variable" should {
    "have been resolved" when {
      "user variable is used and there is logged user" in {
        val variable = createVariable("@{user}")
          .resolve(
            MockRequestContext.default,
            fromRequestContext(MockRequestContext.default).withLoggedUser(LoggedUser(User.Id("simone")))
          )
        variable shouldBe Right("simone")
      }
    }
    "have not been resolved" when {
      "user variable is used but there is no logged user" in {
        val variable = createVariable("@{user}")
          .resolve(
            MockRequestContext.default,
            fromRequestContext(MockRequestContext.default)
          )
        variable shouldBe Left(CannotExtractValue("Cannot extract user ID from block context"))
      }
    }
  }

  "A jwt variable" should {
    "have been resolved" when {
      "jwt variable is used with correct JSON path and JWT token was set" in {

      }
    }
    "have not been resolved" when {
      "JWT token was not set" in {

      }
      "JSON path result is not a string or array of strings" in {

      }
    }
    "have not been able to be created" when {
      "JSON path cannot compile" in {

      }
    }
  }

  "Variables" should {
    "have been resolved" when {
      "@ is used as usual char" in {
        val requestContext = MockRequestContext(headers = Set(headerFrom("key1" -> "x")))

        val variable1 = createVariable("@@@@{key1}")
        variable1.resolve(requestContext, mock[BlockContext]) shouldBe Right("@@@x")

        val variable2 = createVariable("@one@two@{key1}@three@@@")
        variable2.resolve(requestContext, mock[BlockContext]) shouldBe Right("@one@twox@three@@@")

        val variable3 = createVariable(".@one@two.@{key1}@three@@@")
        variable3.resolve(requestContext, mock[BlockContext]) shouldBe Right(".@one@two.x@three@@@")
      }
      "@ can be used as usual char inside header name" in {
        val requestContext = MockRequestContext(headers = Set(headerFrom("@key" -> "x")))
        val variable1 = createVariable("test_@{@key}_sth")
        variable1.resolve(requestContext, mock[BlockContext]) shouldBe Right("test_x_sth")
      }
      "used together" in {
        val requestContext = MockRequestContext(headers = Set(headerFrom("key1" -> "x")))
        val variable = createVariable("u:@{user}_@{key1}")
          .resolve(
            requestContext,
            fromRequestContext(requestContext).withLoggedUser(LoggedUser(User.Id("simone")))
          )
        variable shouldBe Right("u:simone_x")
      }
    }
    "have not been resolved" when {
      "variable is empty string" in {
        VariableCreator.createFrom("h:@{}", Right.apply) shouldBe Left(CreationError("No header name passed"))
      }
    }
  }

  private def createVariable(text: String) = {
    VariableCreator.createFrom(text, Right.apply).right.get
  }

}
