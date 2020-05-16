package tech.beshu.ror.configuration.loader.distributed

import cats.implicits._
import eu.timepit.refined.auto._
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.configuration.loader.LoadedConfig
import tech.beshu.ror.configuration.loader.distributed.NodesResponse.{NodeId, NodeResponse}

import scala.language.postfixOps

class SummaryTest extends WordSpec {
  "Summary" when {
    "there are no configs" should {
      "return no configs" in {
        Summary.create(Nil) shouldBe Summary.NoResult
      }
      "return clear result for only response" in {
        val conf = LoadedConfig.IndexConfig(IndexName("n1"), "config")
        Summary.create(NodeResponse(NodeId(""), conf asRight) :: Nil) shouldBe Summary.ClearResult(conf)
      }
      "return clear result for two identical responses" in {
        val conf = LoadedConfig.IndexConfig(IndexName("config-index"), "config")
        Summary.create(NodeResponse(NodeId("n1"), conf asRight) :: NodeResponse(NodeId("n2"), conf asRight) :: Nil) shouldBe Summary.ClearResult(conf)
      }
      "return ambiguous result for two different responses" in {
        val conf = LoadedConfig.IndexConfig(IndexName("config-index"), "config")
        val conf2 = LoadedConfig.IndexConfig(IndexName("config-index2"), "config2")
        val result = Summary.create(NodeResponse(NodeId("n1"), conf asRight) :: NodeResponse(NodeId("n2"), conf2 asRight) :: Nil).asInstanceOf[Summary.AmbiguousConfigs]
        val resultConfigStatement1 = result.configs.head
        val resultConfigStatement2 = result.configs.get(1).get
        resultConfigStatement1.nodes shouldEqual List(NodeId("n2"))
        resultConfigStatement1.config.right.get shouldEqual conf2
        resultConfigStatement2.nodes shouldEqual List(NodeId("n1"))
        resultConfigStatement2.config.right.get shouldEqual conf
      }
      "accumulate node ids" in {
        val conf = LoadedConfig.IndexConfig(IndexName("config-index"), "config")
        val conf2 = LoadedConfig.IndexConfig(IndexName("config-index2"), "config2")
        val result = Summary.create(NodeResponse(NodeId("n1"), conf asRight) :: NodeResponse(NodeId("n2"), conf2 asRight) :: NodeResponse(NodeId("n3"), conf2 asRight) :: Nil).asInstanceOf[Summary.AmbiguousConfigs]
        val resultConfigStatement1 = result.configs.head
        resultConfigStatement1.nodes shouldEqual List(NodeId("n2"),NodeId("n3"))
      }
    }
  }

}
