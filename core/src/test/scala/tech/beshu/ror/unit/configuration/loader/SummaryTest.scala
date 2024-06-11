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
package tech.beshu.ror.unit.configuration.loader

import cats.implicits._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorConfigurationIndex}
import tech.beshu.ror.configuration.loader.LoadedRorConfig
import tech.beshu.ror.configuration.loader.distributed.NodesResponse.{NodeError, NodeId, NodeResponse}
import tech.beshu.ror.configuration.loader.distributed.Summary.CurrentNodeHaveToProduceResult
import tech.beshu.ror.configuration.loader.distributed.{NodesResponse, Summary}
import tech.beshu.ror.utils.TestsUtils.unsafeNes

import scala.language.postfixOps

class SummaryTest extends AnyWordSpec {
  "Summary" when {
    "there are no configs" should {
      "throw exception, current node have to give response" in {
        assertThrows[CurrentNodeHaveToProduceResult.type] {
          Summary.create(NodeId(""), Nil, Nil)
        }
      }
    }
    "there is no current node config" should {
      "throw no current node config error" in {
        assertThrows[CurrentNodeHaveToProduceResult.type] {
          val conf = LoadedRorConfig.IndexConfig(configIndex("config-index"), "config")
          Summary.create(NodeId(""), NodeResponse(NodeId("b"), conf asRight) :: Nil, Nil)
        }
      }
    }
    "current node has no ror" should {
      "throw error" in {
        assertThrows[CurrentNodeHaveToProduceResult.type] {
          Summary.create(NodeId("a"), Nil, NodeError(NodeId("a"), NodeError.RorConfigActionNotFound) :: Nil)
        }
      }
    }
    "current node has some error" should {
      "throw error" in {
        Summary.create(
          currentNodeId = NodeId("a"),
          nodesResponses = Nil,
          failures = NodeError(NodeId("a"), NodeError.Unknown("exception message")) :: Nil,
        ) shouldEqual
          Summary.CurrentNodeResponseError("exception message").asLeft
      }
    }
    "only node returns config" should {
      "return current node config" in {
        val conf = LoadedRorConfig.IndexConfig(configIndex("config-index"), "config")
        Summary.create(NodeId("a"), NodeResponse(NodeId("a"), conf asRight) :: Nil, Nil) shouldBe
          Summary.Result(conf, Nil).asRight
      }
    }
    "only node returns error" should {
      "return that error" in {
        Summary.create(
          currentNodeId = NodeId("a"),
          nodesResponses = NodeResponse(NodeId("a"), LoadedRorConfig.IndexUnknownStructure asLeft) :: Nil,
          failures = Nil,
        ) shouldBe
          Summary.CurrentNodeConfigError(LoadedRorConfig.IndexUnknownStructure).asLeft
      }
    }
    "current node returns error" should {
      "return that error" in {
        Summary.create(
          currentNodeId = NodeId("a"),
          nodesResponses = NodeResponse(NodeId("a"), LoadedRorConfig.IndexUnknownStructure asLeft) ::
            NodeResponse(NodeId("b"), LoadedRorConfig.IndexConfig(configIndex("config-index"), "config") asRight) ::
            Nil,
          failures = Nil,
        ) shouldBe
          Summary.CurrentNodeConfigError(LoadedRorConfig.IndexUnknownStructure).asLeft
      }
    }
    "other node returns error" should {
      "return config, and other node error as warning" in {
        val conf = LoadedRorConfig.IndexConfig(configIndex("config-index"), "config")
        Summary.create(
          currentNodeId = NodeId("b"),
          nodesResponses = NodeResponse(NodeId("a"), LoadedRorConfig.IndexUnknownStructure asLeft) ::
            NodeResponse(NodeId("b"), conf asRight) ::
            Nil,
          failures = Nil,
        ) shouldBe
          Summary.Result(config = conf,
            warnings = Summary.NodeReturnedConfigError(NodeId("a"), LoadedRorConfig.IndexUnknownStructure) :: Nil,
          ).asRight
      }
    }
    "current node is force loaded from file" should {
      "return config, and forced loading from file as warning" in {
        val conf = LoadedRorConfig.ForcedFileConfig("config")
        Summary.create(NodeId("a"), NodeResponse(NodeId("a"), conf asRight) :: Nil, Nil) shouldBe
          Summary.Result(conf, Summary.NodeForcedFileConfig(NodeId("a")) :: Nil).asRight
      }
    }
    "other node returned unknown error" should {
      "return config, and unknown error warning" in {
        val conf = LoadedRorConfig.ForcedFileConfig("config")
        Summary.create(
          currentNodeId = NodeId("a"),
          nodesResponses = NodeResponse(NodeId("a"), conf asRight) :: Nil,
          failures = NodeError(NodeId("b"), NodeError.Unknown("detailed message")) :: Nil,
        ) shouldBe
          Summary.Result(config = conf,
            warnings = Summary.NodeForcedFileConfig(NodeId("a")) ::
              Summary.NodeReturnedUnknownError(NodeId("b"), "detailed message") ::
              Nil,
          ).asRight
      }
    }
    "other node returned action not found" should {
      "ignore action not found error" in {
        val conf = LoadedRorConfig.ForcedFileConfig("config")
        Summary.create(currentNodeId = NodeId("a"),
          nodesResponses = NodeResponse(NodeId("a"), conf asRight) :: Nil,
          failures = NodeError(NodeId("b"), NodeError.RorConfigActionNotFound) :: Nil,
        ) shouldBe
          Summary.Result(conf, Summary.NodeForcedFileConfig(NodeId("a")) :: Nil).asRight
      }
    }
    "current node returned timeout" should {
      "return error" in {
        Summary.create(NodeId("a"), Nil, NodeError(NodeId("a"), NodeError.Timeout) :: Nil) shouldBe
          Summary.CurrentNodeResponseTimeoutError.asLeft
      }
    }
    "other node has timeout" should {
      "return config, and warning" in {
        val currentConfig = LoadedRorConfig.FileConfig("config1")
        Summary.create(currentNodeId = NodeId("a"),
          nodesResponses = NodeResponse(NodeId("a"), currentConfig asRight) :: Nil,
          failures = NodeError(NodeId("b"), NodesResponse.NodeError.Timeout) :: Nil,
        ) shouldBe
          Summary.Result(currentConfig, Summary.NodeResponseTimeoutWarning(NodeId("b")) :: Nil).asRight
      }
    }
    "other node has different config, than current node" should {
      "return config, and warning" in {
        val currentConfig = LoadedRorConfig.FileConfig("config1")
        val otherConfig = LoadedRorConfig.FileConfig("config2")
        Summary.create(
          currentNodeId = NodeId("a"),
          nodesResponses = NodeResponse(NodeId("a"), currentConfig asRight) ::
            NodeResponse(NodeId("b"), otherConfig asRight) ::
            Nil, failures = Nil,
        ) shouldBe
          Summary.Result(currentConfig, Summary.NodeReturnedDifferentConfig(NodeId("b"), otherConfig) :: Nil).asRight
      }
    }
  }

  private def configIndex(value: NonEmptyString) = RorConfigurationIndex(IndexName.Full(value))
}
