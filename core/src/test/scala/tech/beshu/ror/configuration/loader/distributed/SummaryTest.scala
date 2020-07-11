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
package tech.beshu.ror.configuration.loader.distributed

import cats.implicits._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.configuration.loader.distributed.NodesResponse.{NodeId, NodeResponse}
import tech.beshu.ror.configuration.loader.{LoadedConfig, RorConfigurationIndex}

import scala.language.postfixOps

class SummaryTest extends WordSpec {
  "Summary" when {
    "there are no configs" should {
      "return no node response" in {
        Summary.create2(NodeId(""), Nil) shouldBe Summary.NoCurrentNodeResponse.asLeft
      }
    }
    "there is no current node config" should {
      "return no current node config error" in {
        val conf = LoadedConfig.IndexConfig(configIndex("config-index"), "config")
        Summary.create2(NodeId(""), NodeResponse(NodeId("b"), conf asRight) :: Nil) shouldBe Summary.NoCurrentNodeResponse.asLeft
      }
    }
    "only node returns config" should {
      "return current node config" in {
        val conf = LoadedConfig.IndexConfig(configIndex("config-index"), "config")
        Summary.create2(NodeId("a"), NodeResponse(NodeId("a"), conf asRight) :: Nil) shouldBe Summary.Result("config", Nil).asRight
      }
    }
    "only node returns error" should {
      "return that error" in {
        Summary.create2(NodeId("a"), NodeResponse(NodeId("a"), LoadedConfig.IndexUnknownStructure asLeft) :: Nil) shouldBe Summary.CurrentNodeConfigError(LoadedConfig.IndexUnknownStructure).asLeft
      }
    }
    "current node returns error" should {
      "return that error" in {
        val conf = LoadedConfig.IndexConfig(configIndex("config-index"), "config")
        Summary.create2(NodeId("a"), NodeResponse(NodeId("a"), LoadedConfig.IndexUnknownStructure asLeft) :: NodeResponse(NodeId("b"), conf asRight) :: Nil) shouldBe Summary.CurrentNodeConfigError(LoadedConfig.IndexUnknownStructure).asLeft
      }
    }
    "other node returns error" should {
      "return config, and other node error as warning" in {
        val conf = LoadedConfig.IndexConfig(configIndex("config-index"), "config")
        Summary.create2(NodeId("b"), NodeResponse(NodeId("a"), LoadedConfig.IndexUnknownStructure asLeft) :: NodeResponse(NodeId("b"), conf asRight) :: Nil) shouldBe Summary.Result("config", Summary.NodeReturnedError(NodeId("a"), LoadedConfig.IndexUnknownStructure) :: Nil).asRight
      }
    }
    "current node is force loaded from file" should {
      "return config, and forced loading from file as warning" in {
        val conf = LoadedConfig.ForcedFileConfig("config")
        Summary.create2(NodeId("a"), NodeResponse(NodeId("a"), conf asRight) :: Nil) shouldBe Summary.Result("config", Summary.NodeForcedFileConfig(NodeId("a")) :: Nil).asRight
      }
    }
    "other node has different config, than current node" should {
      "return config, and warning" in {
        val currentConfig = LoadedConfig.FileConfig("config1")
        val otherConfig = LoadedConfig.FileConfig("config2")
        Summary.create2(NodeId("a"), NodeResponse(NodeId("a"), currentConfig asRight) :: NodeResponse(NodeId("b"), otherConfig asRight) :: Nil) shouldBe Summary.Result("config1", Summary.NodeReturnedDifferentConfig(NodeId("b"), LoadedConfig.FileConfig("config2")) :: Nil).asRight
      }
    }
  }

  private def configIndex(value: NonEmptyString) = RorConfigurationIndex(IndexName(value))
}
