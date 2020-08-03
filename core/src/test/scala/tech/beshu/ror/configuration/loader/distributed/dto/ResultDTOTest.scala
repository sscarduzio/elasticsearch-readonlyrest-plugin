package tech.beshu.ror.configuration.loader.distributed.dto

import org.scalatest.WordSpec
import tech.beshu.ror.configuration.loader.distributed.{NodesResponse, Summary}
import cats.implicits._
import org.scalatest.Matchers._
import tech.beshu.ror.configuration.loader.LoadedConfig

import scala.language.postfixOps

class ResultDTOTest extends WordSpec {
  "ResultDTO" when {
    "result failed" should {
      "return current node " in {
        val expectedResult = ResultDTO(None, Nil, "current node returned error: index unknown structure" some)
        ResultDTO.create(Summary.CurrentNodeConfigError(LoadedConfig.IndexUnknownStructure) asLeft) shouldEqual expectedResult
      }
      "return current node failure" in {
        val expectedResult = ResultDTO(None, Nil, "current node response error: null pointer" some)
        ResultDTO.create(Summary.CurrentNodeResponseError("null pointer") asLeft) shouldEqual expectedResult
      }
    }
    "return result" should {
      val warnings = Summary.NodeReturnedConfigError(NodesResponse.NodeId("n2"), LoadedConfig.IndexUnknownStructure) ::
        Summary.NodeForcedFileConfig(NodesResponse.NodeId("n1")) ::
        Nil
      val result = ResultDTO.create(Summary.Result(LoadedConfig.ForcedFileConfig("config"), warnings) asRight)
      "be as DTO" in {
        val expectedResult = ResultDTO(
          config = LoadedConfigDTO.FORCED_FILE_CONFIG("config").some,
          warnings = NodesResponseWaringDTO.NODE_RETURNED_CONFIG_ERROR("n2", "index unknown structure") ::
            NodesResponseWaringDTO.NODE_FORCED_FILE_CONFIG("n1") ::
            Nil,
          error = None,
        )
        result shouldEqual expectedResult
      }
      "be as JSON" in {
        import io.circe.syntax._
        val expectedResult =
          """
            |{
            |  "config" : {
            |    "raw" : "config",
            |    "type" : "FORCED_FILE_CONFIG"
            |  },
            |  "warnings" : [
            |    {
            |      "nodeId" : "n2",
            |      "error" : "index unknown structure",
            |      "type" : "NODE_RETURNED_CONFIG_ERROR"
            |    },
            |    {
            |      "nodeId" : "n1",
            |      "type" : "NODE_FORCED_FILE_CONFIG"
            |    }
            |  ],
            |  "error" : null
            |}""".stripMargin
        result.asJson shouldEqual io.circe.parser.parse(expectedResult).toTry.get
      }
    }
  }

}
