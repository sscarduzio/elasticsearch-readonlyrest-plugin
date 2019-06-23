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

import io.jsonwebtoken.impl.DefaultClaims
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.RequestContextInitiatedBlockContext.fromRequestContext
import tech.beshu.ror.acl.blocks.variables.RuntimeResolvableVariable.Unresolvable.CannotExtractValue
import tech.beshu.ror.acl.blocks.variables.{AlreadyResolved, RuntimeResolvableVariableCreator}
import tech.beshu.ror.acl.blocks.variables.RuntimeResolvableVariableCreator.CreationError
import tech.beshu.ror.acl.domain.{JwtTokenPayload, LoggedUser, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import scala.collection.JavaConverters._

class RuntimeResolvableVariablesTests extends WordSpec with MockFactory {

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
      "given variable has corresponding header in request context and is defined with new format using '${}' syntax" in {
        val variable = createVariable("h_${header:key1}_ok")
          .resolve(
            MockRequestContext(headers = Set(headerFrom("key1" -> "x"))),
            mock[BlockContext]
          )
        variable shouldBe Right("h_x_ok")
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
      "header name is an empty string" in {
        RuntimeResolvableVariableCreator.createFrom("h:@{header:}", Right.apply) shouldBe {
          Left(CreationError("Cannot create header variable, because no header name is passed"))
        }
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
      "jwt variable is used with correct JSON path to string value and JWT token was set" in {
        val variable = createVariable("@{jwt:tech.beshu.mainGroup}")
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
        val variable = createVariable("@{jwt:tech.beshu.groups}")
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
    }
    "have not been resolved" when {
      "JWT token was not set" in {
        val variable = createVariable("@{jwt:tech.beshu.groups}")
          .resolve(
            MockRequestContext.default,
            fromRequestContext(MockRequestContext.default)
          )
        variable shouldBe Left(CannotExtractValue("Cannot extract JSON token payload from block context"))
      }
      "JSON path result is not a string or array of strings" in {
        val variable = createVariable("@{jwt:tech.beshu}")
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
        RuntimeResolvableVariableCreator.createFrom("@{jwt:tech[[.beshu}", Right.apply) shouldBe {
          Left(CreationError("Cannot create JWT variable, because cannot compile 'tech[[.beshu' to JsonPath"))
        }
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
        RuntimeResolvableVariableCreator.createFrom("h:@{}", Right.apply) shouldBe {
          Left(CreationError("Cannot create header variable, because no header name is passed"))
        }
      }
    }
    "have been treated as text" when {
      "variable format doesn't have closing bracket" in {
        RuntimeResolvableVariableCreator.createFrom("h:@{test", Right.apply) shouldBe Right(AlreadyResolved("h:@{test"))
      }
    }
  }

  private def createVariable(text: String) = {
    RuntimeResolvableVariableCreator.createFrom(text, Right.apply).right.get
  }

}
