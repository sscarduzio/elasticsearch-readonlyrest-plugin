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
import io.jsonwebtoken.impl.DefaultClaims
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable.CannotExtractValue
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator.CreationError
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Jwt, User}
import tech.beshu.ror.mocks.{MockRequestContext, MockUserMetadataRequestContext}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.jdk.CollectionConverters.*

class RuntimeResolvableVariablesTests extends AnyWordSpec with MockFactory {

  private val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty))

  "A header variable" should {
    "have been resolved" when {
      "given variable has corresponding header in request context" in {
        val variable = forceCreateSingleVariable("@{key1}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            requestContext = MockRequestContext.metadata.withHeaders(headerFrom("key1" -> "x,y"))
          ))
        variable shouldBe Right("x,y")
      }
      "given multivariable has corresponding header in request context" in {
        val variable = forceCreateMultiVariable("test_@explode{key1}#{to_lowercase}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            requestContext = MockRequestContext.metadata.withHeaders(headerFrom("key1" -> "X,Y,Z"))
          ))
        variable shouldBe Right(NonEmptyList.of("test_x", "test_y", "test_z"))
      }
      "given variable has corresponding header in request context but upper-case" in {
        val variable = forceCreateSingleVariable("h:@{key1}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            requestContext = MockRequestContext.metadata.withHeaders(headerFrom("KEY1" -> "x"))
          ))
        variable shouldBe Right("h:x")
      }
      "given variable has corresponding header in request context and is defined with new format" in {
        val variable = forceCreateSingleVariable("@{header:key1}_ok")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            requestContext = MockRequestContext.metadata.withHeaders(headerFrom("key1" -> "x"))
          ))
        variable shouldBe Right("x_ok")
      }
      "given variable has corresponding header in request context and is defined with new format (with transformation)" in {
        val variable = forceCreateSingleVariable(s"""@{header:key1}#{replace_first("x","z")}_ok""")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            requestContext = MockRequestContext.metadata.withHeaders(headerFrom("key1" -> "x"))
          ))
        variable shouldBe Right("z_ok")
      }
      "given variable has corresponding header in request context and is defined with new format using '${}' syntax" in {
        val variable = forceCreateSingleVariable("h_${header:key1}_ok")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            requestContext = MockRequestContext.metadata.withHeaders(headerFrom("key1" -> "x"))
          ))
        variable shouldBe Right("h_x_ok")
      }
    }
    "have not been resolved" when {
      "given variable doesn't have corresponding header in request context" in {
        val variable = forceCreateSingleVariable("@{key1}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            requestContext = MockRequestContext.metadata.withHeaders(headerFrom("key2" -> "x"))
          ))
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
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withLoggedUser(DirectlyLoggedUser(User.Id("simone")))
          ))
        variable shouldBe Right("simone")
      }
      "user variable is used with namespace and there is logged user" in {
        val variable = forceCreateSingleVariable("@{acl:user}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withLoggedUser(DirectlyLoggedUser(User.Id("simone")))
          ))
        variable shouldBe Right("simone")
      }
      "user variable is used with namespace and variable transformation and there is logged user" in {
        val variable = forceCreateSingleVariable("@{acl:user}#{to_lowercase}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withLoggedUser(DirectlyLoggedUser(User.Id("Simone")))
          ))
        variable shouldBe Right("simone")
      }
      "user multivariable is used and there is logged user" in {
        val variable = forceCreateMultiVariable("@explode{user}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withLoggedUser(DirectlyLoggedUser(User.Id("simone,tony")))
          ))
        variable shouldBe Right(NonEmptyList.of("simone,tony"))
      }
    }
    "have not been resolved" when {
      "user variable is used but there is no logged user" in {
        val variable = forceCreateSingleVariable("@{user}")
          .resolve(currentUserMetadataRequestBlockContextFrom())
        variable shouldBe Left(CannotExtractValue("Cannot extract user ID from block context"))
      }
    }
  }

  "A current group variable" should {
    "have been resolved" when {
      "current group variable is used and some groups has been added as available" in {
        val variable = forceCreateSingleVariable("@{acl:current_group}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withAvailableGroups(UniqueList.of(group("g1"), group("g2")))
          ))
        variable shouldBe Right("g1")
      }
      "current group variable is used and initial group is present" in {
        val requestContext = MockRequestContext.metadata.withHeaders(currentGroupHeader("g1"))
        val variable = forceCreateSingleVariable("@{acl:current_group}")
          .resolve(currentUserMetadataRequestBlockContextFrom(requestContext = requestContext))
        variable shouldBe Right("g1")
      }
      "current group multivariable is used and some groups has been added as available" in {
        val variable = forceCreateMultiVariable("@explode{acl:current_group}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withAvailableGroups(UniqueList.of(group("g1,g2"), group("g3")))
          ))
        variable shouldBe Right(NonEmptyList.of("g1,g2"))
      }
      "current group multivariable with transformation is used and some groups has been added as available" in {
        val variable = forceCreateMultiVariable("""@explode{acl:current_group}#{replace_all("g","group")}""")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withAvailableGroups(UniqueList.of(group("g1,g2"), group("g3")))
          ))
        variable shouldBe Right(NonEmptyList.of("group1,group2"))
      }
    }
    "have not been resolved" when {
      "current group is not available in given block context" in {
        val variable = forceCreateSingleVariable("@{acl:current_group}")
          .resolve(currentUserMetadataRequestBlockContextFrom())
        variable shouldBe Left(CannotExtractValue("There was no current group for request: mock"))
      }
    }
  }

  "An available groups variable" should {
    "have been resolved" when {
      "available groups multivariable is used and explode" in {
        val variable = forceCreateMultiVariable("@explode{acl:available_groups}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withAvailableGroups(UniqueList.of(group("g1"), group("g2")))
          ))
        variable shouldBe Right(NonEmptyList.of("g1", "g2"))
      }
      "available groups multivariable with transformation is used and explode" in {
        val variable = forceCreateMultiVariable("""@explode{acl:available_groups}#{replace_all("g","group").to_uppercase}""")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withAvailableGroups(UniqueList.of(group("g1"), group("g2")))
          ))
        variable shouldBe Right(NonEmptyList.of("GROUP1", "GROUP2"))
      }
      "available groups variable is used without explode" in {
        val variable = forceCreateSingleVariable("@{acl:available_groups}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withAvailableGroups(UniqueList.of(group("g1"), group("g2")))
          ))
        variable shouldBe Right(""""g1","g2"""")
      }
    }
    "have not been resolved" when {
      "available groups are not available in given block context" in {
        val variable = forceCreateMultiVariable("@explode{acl:available_groups}")
          .resolve(currentUserMetadataRequestBlockContextFrom())
        variable shouldBe Left(CannotExtractValue("There were no groups for request: mock"))
      }
    }
  }

  "A jwt variable" should {
    "have been resolved" when {
      "jwt variable is used with correct JSON path to string value and JWT token was set" in {
        val variable = forceCreateSingleVariable("@{jwt:tech.beshu.mainGroup}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withJwtToken(Jwt.Payload {
              val claimsMap = new java.util.HashMap[String, AnyRef]()
              claimsMap.put("tech", Map("beshu" -> Map("mainGroup" -> "group1").asJava).asJava)
              new DefaultClaims(claimsMap)
            })
          ))
        variable shouldBe Right("group1")
      }
      "jwt variable with transformation is used with correct JSON path to string value and JWT token was set" in {
        val variable = forceCreateSingleVariable("""@{jwt:tech.beshu.mainGroup}#{replace_first("^group\d","g")}""")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withJwtToken(Jwt.Payload {
              val claimsMap = new java.util.HashMap[String, AnyRef]()
              claimsMap.put("tech", Map("beshu" -> Map("mainGroup" -> "group1").asJava).asJava)
              new DefaultClaims(claimsMap)
            })
          ))
        variable shouldBe Right("g")
      }
      "jwt variable is used with correct JSON path to strings array value and JWT token was set" in {
        val variable = forceCreateSingleVariable("@{jwt:tech.beshu.groups}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withJwtToken(
              Jwt.Payload {
                val claimsMap = new java.util.HashMap[String, AnyRef]()
                claimsMap.put("tech", Map("beshu" -> Map("groups" -> List("group1", "group2").asJava).asJava).asJava)
                new DefaultClaims(claimsMap)
              }
            )
          ))
        variable shouldBe Right("\"group1\",\"group2\"")
      }
      "jwt multivariable is used with correct JSON path to strings array value and JWT token was set" in {
        val variable = forceCreateMultiVariable("@explode{jwt:tech.beshu.groups}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withJwtToken(Jwt.Payload {
              val claimsMap = new java.util.HashMap[String, AnyRef]()
              claimsMap.put("tech", Map("beshu" -> Map("groups" -> List("group1", "group2").asJava).asJava).asJava)
              new DefaultClaims(claimsMap)
            })
          ))
        variable shouldBe Right(NonEmptyList.of("group1", "group2"))
      }
    }
    "have not been resolved" when {
      "JWT token was not set" in {
        val variable = forceCreateSingleVariable("@{jwt:tech.beshu.groups}")
          .resolve(currentUserMetadataRequestBlockContextFrom())
        variable shouldBe Left(CannotExtractValue("Cannot extract JSON token payload from block context"))
      }
      "JSON path result is not a string or array of strings" in {
        val variable = forceCreateSingleVariable("@{jwt:tech.beshu}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withJwtToken(
              Jwt.Payload {
                val claimsMap = new java.util.HashMap[String, AnyRef]()
                claimsMap.put("tech", Map("beshu" -> Map("groups" -> List("group1", "group2").asJava).asJava).asJava)
                new DefaultClaims(claimsMap)
              }
            )
          ))
        variable shouldBe Left(CannotExtractValue("Cannot find value string or collection of strings in path 'tech.beshu' of JWT Token"))
      }
    }
    "have not been able to be created" when {
      "JSON path cannot compile" in {
        val variable = variableCreator.createSingleResolvableVariableFrom("@{jwt:tech[[.beshu}")(
          AlwaysRightConvertible.stringAlwaysRightConvertible,
        )
        variable should be(Left(CreationError.InvalidVariableDefinition("cannot compile 'tech[[.beshu' to JsonPath")))
      }
    }
  }

  "Variables" should {
    "have been resolved" when {
      "@ is used as usual char" in {
        val requestContext = MockRequestContext.metadata.withHeaders(headerFrom("key1" -> "x"))

        val variable1 = forceCreateSingleVariable("@@@@{key1}")
        variable1.resolve(currentUserMetadataRequestBlockContextFrom(requestContext = requestContext)) shouldBe Right("@@@x")

        val variable2 = forceCreateSingleVariable("@one@two@{key1}@three@@@")
        variable2.resolve(currentUserMetadataRequestBlockContextFrom(requestContext = requestContext)) shouldBe Right("@one@twox@three@@@")

        val variable3 = forceCreateSingleVariable(".@one@two.@{key1}@three@@@")
        variable3.resolve(currentUserMetadataRequestBlockContextFrom(requestContext = requestContext)) shouldBe Right(".@one@two.x@three@@@")
      }
      "@ can be used as usual char inside header name" in {
        val requestContext = MockRequestContext.metadata.withHeaders(headerFrom("@key" -> "x"))
        val variable1 = forceCreateSingleVariable("test_@{@key}_sth")
        variable1.resolve(currentUserMetadataRequestBlockContextFrom(requestContext = requestContext)) shouldBe Right("test_x_sth")
      }
      "used together" in {
        val requestContext = MockRequestContext.metadata.withHeaders(headerFrom("key1" -> "x"))
        val variable = forceCreateSingleVariable("u:@{user}_@{key1}")
          .resolve(currentUserMetadataRequestBlockContextFrom(
            _.withLoggedUser(DirectlyLoggedUser(User.Id("simone"))),
            requestContext = requestContext
          ))
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

  private def forceCreateSingleVariable(text: String) = createSingleVariable(text).fold(
    m => throw new IllegalStateException(m.toString),
    identity
  )

  private def createSingleVariable(text: String) = {
    variableCreator.createSingleResolvableVariableFrom(NonEmptyString.unsafeFrom(text))(
      AlwaysRightConvertible.stringAlwaysRightConvertible
    )
  }

  private def forceCreateMultiVariable(text: String) = createMultiVariable(text).toOption.get

  private def createMultiVariable(text: String) = {
    variableCreator.createMultiResolvableVariableFrom(NonEmptyString.unsafeFrom(text))(
      AlwaysRightConvertible.stringAlwaysRightConvertible,
    )
  }

  private def currentUserMetadataRequestBlockContextFrom(update: UserMetadata => UserMetadata = identity,
                                                         requestContext: MockUserMetadataRequestContext = MockRequestContext.metadata) = {
    CurrentUserMetadataRequestBlockContext(
      requestContext,
      update(UserMetadata.from(requestContext)),
      Set.empty,
      List.empty
    )
  }
}
