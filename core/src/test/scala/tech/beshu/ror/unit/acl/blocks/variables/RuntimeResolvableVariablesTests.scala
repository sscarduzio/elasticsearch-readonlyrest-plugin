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
import io.jsonwebtoken.impl.DefaultClaims
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.RequestContextInitiatedBlockContext.fromRequestContext
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable.CannotExtractValue
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeSingleResolvableVariable.AlreadyResolved
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariableCreator.{CreationError, createMultiResolvableVariableFrom, createSingleResolvableVariableFrom}
import tech.beshu.ror.acl.domain.{JwtTokenPayload, LoggedUser, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

import scala.collection.JavaConverters._

class RuntimeResolvableVariablesTests extends WordSpec with MockFactory {

  "A header variable" should {
    "have been resolved" when {
      "given variable has corresponding header in request context" in {
        val variable = forceCreateSingleVariable("@{key1}")
          .resolve(
            MockRequestContext(headers = Set(headerFrom("key1" -> "x,y"))),
            mock[BlockContext]
          )
        variable shouldBe Right("x,y")
      }
      "given multivariable has corresponding header in request context" in {
        val variable = forceCreateMultiVariable("test_@explode{key1}")
          .resolve(
            MockRequestContext(headers = Set(headerFrom("key1" -> "x,y,z"))),
            mock[BlockContext]
          )
        variable shouldBe Right(NonEmptyList.of("test_x", "test_y", "test_z"))
      }
      "given variable has corresponding header in request context but upper-case" in {
        val variable = forceCreateSingleVariable("h:@{key1}")
          .resolve(
            MockRequestContext(headers = Set(headerFrom("KEY1" -> "x"))),
            mock[BlockContext]
          )
        variable shouldBe Right("h:x")
      }
      "given variable has corresponding header in request context and is defined with new format" in {
        val variable = forceCreateSingleVariable("@{header:key1}_ok")
          .resolve(
            MockRequestContext(headers = Set(headerFrom("key1" -> "x"))),
            mock[BlockContext]
          )
        variable shouldBe Right("x_ok")
      }
      "given variable has corresponding header in request context and is defined with new format using '${}' syntax" in {
        val variable = forceCreateSingleVariable("h_${header:key1}_ok")
          .resolve(
            MockRequestContext(headers = Set(headerFrom("key1" -> "x"))),
            mock[BlockContext]
          )
        variable shouldBe Right("h_x_ok")
      }
    }
    "have not been resolved" when {
      "given variable doesn't have corresponding header in request context" in {
        val variable = forceCreateSingleVariable("@{key1}")
          .resolve(
            MockRequestContext(headers = Set(headerFrom("key2" -> "x"))),
            mock[BlockContext]
          )
        variable shouldBe Left(CannotExtractValue("Cannot extract user header 'key1' from request context"))
      }
    }
    "have not been able to be created" when {
      "header name is an empty string" in {
        createSingleVariable("h:@{header:}") shouldBe Left(CreationError.InvalidVariableDefinition("No header name is passed"))
      }
      "two multivariables are used" in {
        createMultiVariable("@explode{header:h1}_@explode{header:h2}") shouldBe {
          Left(CreationError.OnlyOneMultiVariableCanBeUsedInVariableDefinition)
        }
      }
      "multivariables is used in single variable context" in {
        createSingleVariable("@explode{header:h1}_") shouldBe {
          Left(CreationError.CannotUserMultiVariableInSingleVariableContext)
        }
      }
    }
  }

  "A user variable" should {
    "have been resolved" when {
      "user variable is used and there is logged user" in {
        val variable = forceCreateSingleVariable("@{user}")
          .resolve(
            MockRequestContext.default,
            fromRequestContext(MockRequestContext.default).withLoggedUser(LoggedUser(User.Id("simone")))
          )
        variable shouldBe Right("simone")
      }
      "user multivariable is used and there is logged user" in {
        val variable = forceCreateMultiVariable("@explode{user}")
          .resolve(
            MockRequestContext.default,
            fromRequestContext(MockRequestContext.default).withLoggedUser(LoggedUser(User.Id("simone,tony")))
          )
        variable shouldBe Right(NonEmptyList.of("simone", "tony"))
      }
    }
    "have not been resolved" when {
      "user variable is used but there is no logged user" in {
        val variable = forceCreateSingleVariable("@{user}")
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
      "jwt variable is used with correct JSON path to string value and JWT token was set" in {
        val variable = forceCreateSingleVariable("@{jwt:tech.beshu.mainGroup}")
          .resolve(
            MockRequestContext.default,
            fromRequestContext(MockRequestContext.default)
              .withJsonToken(JwtTokenPayload {
                val claims = new DefaultClaims()
                claims.put("tech", Map("beshu" -> Map("mainGroup" -> "group1").asJava).asJava)
                claims
              })
          )
        variable shouldBe Right("group1")
      }
      "jwt variable is used with correct JSON path to strings array value and JWT token was set" in {
        val variable = forceCreateSingleVariable("@{jwt:tech.beshu.groups}")
          .resolve(
            MockRequestContext.default,
            fromRequestContext(MockRequestContext.default)
              .withJsonToken(JwtTokenPayload {
                val claims = new DefaultClaims()
                claims.put("tech", Map("beshu" -> Map("groups" -> List("group1", "group2").asJava).asJava).asJava)
                claims
              })
          )
        variable shouldBe Right("group1,group2")
      }
      "jwt multivariable is used with correct JSON path to strings array value and JWT token was set" in {
        val variable = forceCreateMultiVariable("@explode{jwt:tech.beshu.groups}")
          .resolve(
            MockRequestContext.default,
            fromRequestContext(MockRequestContext.default)
              .withJsonToken(JwtTokenPayload {
                val claims = new DefaultClaims()
                claims.put("tech", Map("beshu" -> Map("groups" -> List("group1", "group2").asJava).asJava).asJava)
                claims
              })
          )
        variable shouldBe Right(NonEmptyList.of("group1", "group2"))
      }
    }
    "have not been resolved" when {
      "JWT token was not set" in {
        val variable = forceCreateSingleVariable("@{jwt:tech.beshu.groups}")
          .resolve(
            MockRequestContext.default,
            fromRequestContext(MockRequestContext.default)
          )
        variable shouldBe Left(CannotExtractValue("Cannot extract JSON token payload from block context"))
      }
      "JSON path result is not a string or array of strings" in {
        val variable = forceCreateSingleVariable("@{jwt:tech.beshu}")
          .resolve(
            MockRequestContext.default,
            fromRequestContext(MockRequestContext.default)
              .withJsonToken(JwtTokenPayload {
                val claims = new DefaultClaims()
                claims.put("tech", Map("beshu" -> Map("groups" -> List("group1", "group2").asJava).asJava).asJava)
                claims
              })
          )
        variable shouldBe Left(CannotExtractValue("Cannot find value string or collection of strings in path '$['tech']['beshu']' of JWT Token"))
      }
    }
    "have not been able to be created" when {
      "JSON path cannot compile" in {
        createSingleResolvableVariableFrom("@{jwt:tech[[.beshu}".nonempty)(AlwaysRightConvertible.stringAlwaysRightConvertible) shouldBe {
          Left(CreationError.InvalidVariableDefinition("cannot compile 'tech[[.beshu' to JsonPath"))
        }
      }
    }
  }

  "Variables" should {
    "have been resolved" when {
      "@ is used as usual char" in {
        val requestContext = MockRequestContext(headers = Set(headerFrom("key1" -> "x")))

        val variable1 = forceCreateSingleVariable("@@@@{key1}")
        variable1.resolve(requestContext, mock[BlockContext]) shouldBe Right("@@@x")

        val variable2 = forceCreateSingleVariable("@one@two@{key1}@three@@@")
        variable2.resolve(requestContext, mock[BlockContext]) shouldBe Right("@one@twox@three@@@")

        val variable3 = forceCreateSingleVariable(".@one@two.@{key1}@three@@@")
        variable3.resolve(requestContext, mock[BlockContext]) shouldBe Right(".@one@two.x@three@@@")
      }
      "@ can be used as usual char inside header name" in {
        val requestContext = MockRequestContext(headers = Set(headerFrom("@key" -> "x")))
        val variable1 = forceCreateSingleVariable("test_@{@key}_sth")
        variable1.resolve(requestContext, mock[BlockContext]) shouldBe Right("test_x_sth")
      }
      "used together" in {
        val requestContext = MockRequestContext(headers = Set(headerFrom("key1" -> "x")))
        val variable = forceCreateSingleVariable("u:@{user}_@{key1}")
          .resolve(
            requestContext,
            fromRequestContext(requestContext).withLoggedUser(LoggedUser(User.Id("simone")))
          )
        variable shouldBe Right("u:simone_x")
      }
    }
    "have not been resolved" when {
      "variable is empty string" in {
        createSingleVariable("h:@{}") shouldBe Left(CreationError.InvalidVariableDefinition("No header name is passed"))
      }
    }
    "have been treated as text" when {
      "variable format doesn't have closing bracket" in {
        createSingleVariable("h:@{test") shouldBe Right(AlreadyResolved("h:@{test"))
      }
    }
  }

  private def forceCreateSingleVariable(text: String) = createSingleVariable(text).right.get
  private def createSingleVariable(text: String) = {
    createSingleResolvableVariableFrom(text.nonempty)(AlwaysRightConvertible.stringAlwaysRightConvertible)
  }

  private def forceCreateMultiVariable(text: String) = createMultiVariable(text).right.get
  private def createMultiVariable(text: String) = {
    createMultiResolvableVariableFrom(text.nonempty)(AlwaysRightConvertible.stringAlwaysRightConvertible)
  }
}
