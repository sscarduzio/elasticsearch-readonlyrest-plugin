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
package tech.beshu.ror.unit.acl.blocks.rules.elasticsearch

import cats.data.NonEmptySet
import eu.timepit.refined.types.string.NonEmptyString
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.NotAuthorized
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.DataStreamsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.domain.{Action, DataStreamName, LoggedUser, User}
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

import scala.language.postfixOps

class DataStreamsRuleTests extends AnyWordSpec with MockFactory {

  "A DataStreamsRule" should {
    "match" when {
      "allowed data streams set contains *" in {
        assertMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.Wildcard.nel)),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("data_stream1").get)
        ) {
          blockContext => blockContext.dataStreams should be(Set(DataStreamName.fromString("data_stream1").get))
        }
      }
      "allowed data streams set contains _all" in {
        assertMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.All.nel)),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("data_stream1").get)
        ) {
          blockContext => blockContext.dataStreams should be(Set(DataStreamName.fromString("data_stream1").get))
        }
      }
      "readonly request with configured simple data stream" in {
        assertMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("public-asd").get.nel)),
          requestAction = Action("indices:admin/data_stream/create"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get)
        ) {
          blockContext => blockContext.dataStreams should be(Set(DataStreamName.fromString("public-asd").get))
        }
      }
      "readonly request with configured data stream with wildcard" in {
        assertMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("public-*").get.nel)),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get)
        ) {
          blockContext => blockContext.dataStreams should be(Set(DataStreamName.fromString("public-asd").get))
        }
      }
      "write request with configured simple data stream" in {
        assertMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("public-asd").get.nel)),
          requestAction = Action("indices:admin/data_stream/create"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get)
        ) {
          blockContext => blockContext.dataStreams should be(Set(DataStreamName.fromString("public-asd").get))
        }
      }
      "write request with configured data stream with wildcard" in {
        assertMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("public-*").get.nel)),
          requestAction = Action("indices:admin/data_stream/create"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get)
        ) {
          blockContext => blockContext.dataStreams should be(Set(DataStreamName.fromString("public-asd").get))
        }
      }
      "readonly request with configured several data streams and several data streams in request" in {
        assertMatchRule(
          configuredDataStreams = NonEmptySet.of(
            AlreadyResolved(DataStreamName.fromString("public-*").get.nel),
            AlreadyResolved(DataStreamName.fromString("n").get.nel)
          ),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get, DataStreamName.fromString("q").get)
        ) {
          blockContext => blockContext.dataStreams should be(Set(DataStreamName.fromString("public-asd").get))
        }
      }
    }
    "not match" when {
      "request is read only" in {
        assertNotMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("x-*").get.nel)),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get)
        )
      }
      "write request with no match" in {
        assertNotMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("public-*").get.nel)),
          requestAction = Action("indices:admin/data_stream/create"),
          requestDataStreams = Set(DataStreamName.fromString("x_public-asd").get)
        )
      }
      "write request with configured several data streams and several data streams in request" in {
        assertNotMatchRule(
          configuredDataStreams = NonEmptySet.of(
            AlreadyResolved(DataStreamName.fromString("public-*").get.nel),
            AlreadyResolved(DataStreamName.fromString("n").get.nel)
          ),
          requestAction = Action("indices:admin/data_stream/create"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get, DataStreamName.fromString("q").get)
        )
      }
      "write request forbid" in {
        assertNotMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("x-*").get.nel)),
          requestAction = Action("indices:admin/data_stream/create"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get, DataStreamName.fromString("q").get)
        )
      }
      "read request forbid" in {
        assertNotMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("x-*").get.nel)),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get, DataStreamName.fromString("q").get)
        )
      }
    }
    "match a runtime variable" when {
      "it resolves to the requested data stream" in {
        assertMatchRule(
          configuredDataStreams = NonEmptySet.one(dataStreamNameVar("@{user}")),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("user-stream").get),
          loggedUser = Some(LoggedUser.DirectlyLoggedUser(User.Id("user-stream")))
        ) {
          blockContext => blockContext.dataStreams should be(Set(DataStreamName.fromString("user-stream").get))
        }
      }
    }
    "not match a runtime variable" when {
      "it resolves to a data stream different from the requested one" in {
        assertNotMatchRule(
          configuredDataStreams = NonEmptySet.one(dataStreamNameVar("@{user}")),
          requestAction = Action("indices:admin/data_stream/create"),
          requestDataStreams = Set(DataStreamName.fromString("other-stream").get),
          loggedUser = Some(LoggedUser.DirectlyLoggedUser(User.Id("user-stream")))
        )
      }
    }
  }

  private def assertMatchRule(configuredDataStreams: NonEmptySet[RuntimeMultiResolvableVariable[DataStreamName]],
                              requestAction: Action,
                              requestDataStreams: Set[DataStreamName],
                              loggedUser: Option[LoggedUser.DirectlyLoggedUser] = None)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredDataStreams, requestAction, requestDataStreams, loggedUser, RuleCheckAssertion.RulePermitted(blockContextAssertion))

  private def assertNotMatchRule(configuredDataStreams: NonEmptySet[RuntimeMultiResolvableVariable[DataStreamName]],
                                 requestAction: Action,
                                 requestDataStreams: Set[DataStreamName],
                                 loggedUser: Option[LoggedUser.DirectlyLoggedUser] = None): Unit =
    assertRule(configuredDataStreams, requestAction, requestDataStreams, loggedUser, RuleCheckAssertion.RuleDenied(NotAuthorized))

  private def assertRule(configuredDataStreams: NonEmptySet[RuntimeMultiResolvableVariable[DataStreamName]],
                         requestAction: Action,
                         requestDataStreams: Set[DataStreamName],
                         loggedUser: Option[LoggedUser.DirectlyLoggedUser],
                         assertion: RuleCheckAssertion): Unit = {
    val rule = new DataStreamsRule(DataStreamsRule.Settings(configuredDataStreams))
    val requestContext = MockRequestContext.dataStreams.copy(
      dataStreams = requestDataStreams,
      action = requestAction
    )
    val blockMetadata = loggedUser.foldLeft(BlockMetadata.empty)(_.withLoggedUser(_))
    val blockContext = DataStreamRequestBlockContext(
      mock[Block], requestContext, blockMetadata, Set.empty, List.empty, requestDataStreams, BackingIndices.IndicesNotInvolved
    )
    rule.checkAndAssert(blockContext, assertion)
  }

  private def dataStreamNameVar(value: String): RuntimeMultiResolvableVariable[DataStreamName] = {
    implicit val convertible: AlwaysRightConvertible[DataStreamName] =
      AlwaysRightConvertible.from(str => DataStreamName.fromString(str.value).getOrElse(DataStreamName.All))
    variableCreator
      .createMultiResolvableVariableFrom(NonEmptyString.unsafeFrom(value))
      .getOrElse(throw new IllegalStateException(s"Cannot create DataStreamName variable from $value"))
  }

  private val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty))

}
