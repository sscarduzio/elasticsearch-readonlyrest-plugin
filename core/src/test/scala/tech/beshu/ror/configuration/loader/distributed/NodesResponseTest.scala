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

import io.circe.Json
import org.scalatest.{Assertion, WordSpec}
import tech.beshu.ror.configuration.loader.distributed.NodesResponse.{ClusterName, NodeId, NodeResponse}
import org.scalatest.Matchers._
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.configuration.loader.LoadedConfig.{FileRecoveredConfig, ForcedFileConfig, IndexConfig, IndexParsingError}
import eu.timepit.refined.auto._
class NodesResponseTest extends WordSpec {
  "Encoding NodesResponse" when {
    "is empty" should {
      "give empty json" in {
        NodesResponse.create(ClusterName("cluster1"), Nil, Nil).toJson shouldEqualJson """{"clusterName":"cluster1","responses":[],"failures":[],"summary":{"type":"no_result","value":{}}}"""
      }
    }
    "has FileRecoveredConfig config because of no index config" should {
      "encode as json" in {
        val result = NodesResponse.create(ClusterName("cluster1"), NodeResponse(NodeId("node1"), Right(FileRecoveredConfig("a", FileRecoveredConfig.indexNotExist))) :: Nil, Nil).toJson
        result shouldEqualJson """{"clusterName":"cluster1","responses":[{"type":"FileRecoveredConfig","message":null,"path":null,"config":"a","cause":"IndexNotExist","indexName":null,"nodeId":"node1"}],"failures":[],"summary":{"type":"clear_result","value":{"type":"FileRecoveredConfig","message":null,"path":null,"config":"a","cause":"IndexNotExist","indexName":null}}}"""
      }
    }
    "has FileRecoveredConfig config because of IndexParsingError" should {
      "encode as json" in {
        val result = NodesResponse.create(ClusterName("cluster1"), NodeResponse(NodeId("node1"), Right(FileRecoveredConfig("a", FileRecoveredConfig.indexParsingError(IndexParsingError("error"))))) :: Nil, Nil).toJson
        result shouldEqualJson """{"clusterName":"cluster1","responses":[{"type":"FileRecoveredConfig","message":"error","path":null,"config":"a","cause":"IndexParsingError","indexName":null,"nodeId":"node1"}],"failures":[],"summary":{"type":"clear_result","value":{"type":"FileRecoveredConfig","message":"error","path":null,"config":"a","cause":"IndexParsingError","indexName":null}}}"""
      }
    }
    "has ForcedFileConfig config" should {
      "encode as json" in {
        val result = NodesResponse.create(ClusterName("cluster1"), NodeResponse(NodeId("node1"), Right(ForcedFileConfig("a"))) :: Nil, Nil).toJson
        result shouldEqualJson """{"clusterName":"cluster1","responses":[{"type":"ForcedFileConfig","message":null,"path":null,"config":"a","cause":null,"indexName":null,"nodeId":"node1"}],"failures":[],"summary":{"type":"clear_result","value":{"type":"ForcedFileConfig","message":null,"path":null,"config":"a","cause":null,"indexName":null}}}"""
      }
    }
    "has IndexConfig config" should {
      "encode as json" in {
        val result = NodesResponse.create(ClusterName("cluster1"), NodeResponse(NodeId("node1"), Right(IndexConfig(IndexName("config"), "a"))) :: Nil, Nil).toJson
        result shouldEqualJson """{"clusterName":"cluster1","responses":[{"type":"IndexConfig","message":null,"path":null,"config":"a","cause":null,"indexName":"config","nodeId":"node1"}],"failures":[],"summary":{"type":"clear_result","value":{"type":"IndexConfig","message":null,"path":null,"config":"a","cause":null,"indexName":"config"}}}"""
      }
    }
    "has File config" should {
      "encode as json" in {
        val result = NodesResponse.create(ClusterName("cluster1"), NodeResponse(NodeId("node1"), Right(IndexConfig(IndexName("config"), "a"))) :: Nil, Nil).toJson
        result shouldEqualJson """{"clusterName":"cluster1","responses":[{"type":"IndexConfig","message":null,"path":null,"config":"a","cause":null,"indexName":"config","nodeId":"node1"}],"failures":[],"summary":{"type":"clear_result","value":{"type":"IndexConfig","message":null,"path":null,"config":"a","cause":null,"indexName":"config"}}}"""
      }
    }
  }
  implicit class OpsJsonEqual(left: String) {
    println(left)

    def shouldEqualJson(input: String): Assertion = parseJson(left) shouldEqual parseJson(input)
  }
  private def parseJson(input: String): Json = io.circe.parser.parse(input).toTry.get
}
