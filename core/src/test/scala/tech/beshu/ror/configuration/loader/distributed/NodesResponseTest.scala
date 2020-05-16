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
