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
package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.DataStreamsRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.{Action, DataStreamName}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

import scala.concurrent.duration._
import scala.language.postfixOps

class DataStreamsRuleTests extends AnyWordSpec with Inside {

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
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get),
          readonlyRequest = true
        ) {
          blockContext => blockContext.dataStreams should be(Set(DataStreamName.fromString("public-asd").get))
        }
      }
      "readonly request with configured data stream with wildcard" in {
        assertMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("public-*").get.nel)),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get),
          readonlyRequest = true
        ) {
          blockContext => blockContext.dataStreams should be(Set(DataStreamName.fromString("public-asd").get))
        }
      }
      "write request with configured simple data stream" in {
        assertMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("public-asd").get.nel)),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get)
        ) {
          blockContext => blockContext.dataStreams should be(Set(DataStreamName.fromString("public-asd").get))
        }
      }
      "write request with configured data stream with wildcard" in {
        assertMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("public-*").get.nel)),
          requestAction = Action("indices:admin/data_stream/get"),
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
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get, DataStreamName.fromString("q").get),
          readonlyRequest = true
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
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get),
          readonlyRequest = true
        )
      }
      "write request with no match" in {
        assertNotMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("public-*").get.nel)),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("x_public-asd").get)
        )
      }
      "write request with configured several data streams and several data streams in request" in {
        assertNotMatchRule(
          configuredDataStreams = NonEmptySet.of(
            AlreadyResolved(DataStreamName.fromString("public-*").get.nel),
            AlreadyResolved(DataStreamName.fromString("n").get.nel)
          ),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get, DataStreamName.fromString("q").get)
        )
      }
      "write request forbid" in {
        assertNotMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("x-*").get.nel)),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get, DataStreamName.fromString("q").get)
        )
      }
      "read request forbid" in {
        assertNotMatchRule(
          configuredDataStreams = NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("x-*").get.nel)),
          requestAction = Action("indices:admin/data_stream/get"),
          requestDataStreams = Set(DataStreamName.fromString("public-asd").get, DataStreamName.fromString("q").get),
          readonlyRequest = true
        )
      }
    }
  }

  private def assertMatchRule(configuredDataStreams: NonEmptySet[RuntimeMultiResolvableVariable[DataStreamName]],
                              requestAction: Action,
                              requestDataStreams: Set[DataStreamName],
                              readonlyRequest: Boolean = false)
                             (blockContextAssertion: DataStreamRequestBlockContext => Unit): Unit =
    assertRule(configuredDataStreams, requestAction, requestDataStreams, readonlyRequest, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredDataStreams: NonEmptySet[RuntimeMultiResolvableVariable[DataStreamName]],
                                 requestAction: Action,
                                 requestDataStreams: Set[DataStreamName],
                                 readonlyRequest: Boolean = false): Unit =
    assertRule(configuredDataStreams, requestAction, requestDataStreams, readonlyRequest, blockContextAssertion = None)

  private def assertRule(configuredDataStreams: NonEmptySet[RuntimeMultiResolvableVariable[DataStreamName]],
                         requestAction: Action,
                         requestDataStreams: Set[DataStreamName],
                         readonlyRequest: Boolean,
                         blockContextAssertion: Option[DataStreamRequestBlockContext => Unit]) = {
    val rule = new DataStreamsRule(DataStreamsRule.Settings(configuredDataStreams))
    val requestContext = MockRequestContext.dataStreams.copy(
      dataStreams = requestDataStreams,
      action = requestAction,
      isReadOnlyRequest = readonlyRequest
    )
    val blockContext = DataStreamRequestBlockContext(
      requestContext, UserMetadata.empty, Set.empty, List.empty, requestDataStreams, BackingIndices.IndicesNotInvolved
    )
    val result = rule.check(blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected())
    }
  }

}
