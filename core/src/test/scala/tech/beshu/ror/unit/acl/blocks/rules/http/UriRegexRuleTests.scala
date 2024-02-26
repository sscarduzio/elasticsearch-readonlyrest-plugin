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
package tech.beshu.ror.unit.acl.blocks.rules.http

import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.http.UriRegexRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.ConvertError
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{UriPath, User}
import tech.beshu.ror.accesscontrol.orders.patternOrder
import tech.beshu.ror.mocks.MockRequestContext

import java.util.regex.Pattern
import scala.util.Try

class UriRegexRuleTests extends AnyWordSpec {

  "An UriRegexRule" should {
    "match" when {
      "configured pattern matches uri from request" in {
        assertMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/\d\d\w$""")),
          uriPath = UriPath("/123")
        )
      }
      "second configured pattern on list matches uri from request" in {
        assertMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/\d$""", """^\/\d\d\d$""")),
          uriPath = UriPath("/123")
        )
      }
      "configured pattern with variable matches uri from request when user is logged" in {
        assertMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/@{user}$""")),
          uriPath = UriPath("/mia"),
          loggedUser = Some(User.Id("mia"))
        )
      }
      "configured pattern with variable containing namespace matches uri from request when user is logged" in {
        assertMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/@{acl:user}$""")),
          uriPath = UriPath("/mia"),
          loggedUser = Some(User.Id("mia"))
        )
      }
      "second configured pattern with variable matches uri from request when user is logged" in {
        assertMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/mi\d$""", """^\/@{user}$""")),
          uriPath = UriPath("/mia"),
          loggedUser = Some(User.Id("mia"))
        )
      }
    }
    "not matched" when {
      "configured pattern doesn't match uri from request" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""\/\d\d\d$""")),
          uriPath = UriPath("/one")
        )
      }
      "none of configured patterns matches uri from request" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""\/\d\d\d$""", """\/\d$""", """\/\w\w\d$""")),
          uriPath = UriPath("/one")
        )
      }
      "configured pattern with variable doesn't match uri from request when user is not logged" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/@{user}$""")),
          uriPath = UriPath("/mia")
        )
      }
      "configured pattern with variable isn't able to compile to pattern after resolve" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/@{user}$""")),
          uriPath = UriPath("/mia"),
          loggedUser = Some(User.Id("["))
        )
      }
    }
  }

  private def assertMatchRule(uriRegex: NonEmptySet[RuntimeMultiResolvableVariable[Pattern]],
                              uriPath: UriPath,
                              loggedUser: Option[User.Id] = None) =
    assertRule(uriRegex, uriPath, loggedUser, isMatched = true)

  private def assertNotMatchRule(uriRegex: NonEmptySet[RuntimeMultiResolvableVariable[Pattern]],
                                 uriPath: UriPath,
                                 loggedUser: Option[User.Id] = None) =
    assertRule(uriRegex, uriPath, loggedUser, isMatched = false)

  private def assertRule(uriRegex: NonEmptySet[RuntimeMultiResolvableVariable[Pattern]],
                         uriPath: UriPath,
                         loggedUser: Option[User.Id],
                         isMatched: Boolean) = {
    val rule = new UriRegexRule(UriRegexRule.Settings(uriRegex))
    val requestContext = MockRequestContext.metadata.copy(uriPath = uriPath)
    val blockContext = CurrentUserMetadataRequestBlockContext(
      requestContext = requestContext,
      userMetadata = loggedUser match {
        case Some(userId) => UserMetadata.empty.withLoggedUser(DirectlyLoggedUser(userId))
        case None => UserMetadata.empty
      },
      responseHeaders = Set.empty,
      responseTransformations = List.empty
    )
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }

  private def patternValueFrom(values: NonEmptySet[String]): NonEmptySet[RuntimeMultiResolvableVariable[Pattern]] = {
    values
      .map { value =>
        implicit val patternConvertible: Convertible[Pattern] = new Convertible[Pattern] {
          override def convert: String => Either[Convertible.ConvertError, Pattern] = str => {
            Try(Pattern.compile(str)).toEither.left.map(_ => ConvertError("msg"))
          }
        }
        variableCreator
          .createMultiResolvableVariableFrom[Pattern](NonEmptyString.unsafeFrom(value))
          .getOrElse(throw new IllegalStateException(s"Cannot create Pattern Value from $value"))
      }
  }

  private val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty))
}
