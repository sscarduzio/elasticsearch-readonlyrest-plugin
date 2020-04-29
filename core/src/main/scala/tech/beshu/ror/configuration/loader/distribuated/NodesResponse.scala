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
package tech.beshu.ror.configuration.loader.distribuated

import cats.implicits._
import io.circe.generic.extras.semiauto.deriveUnwrappedEncoder
import io.circe.generic.semiauto
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}
import shapeless.{Inl, Inr}
import tech.beshu.ror.configuration.loader.{LoadedConfig, Path}
import tech.beshu.ror.configuration.loader.LoadedConfig.FileRecoveredConfig.Cause
import tech.beshu.ror.configuration.loader.distribuated.NodesResponse.{ClusterName, NodeError, NodeResponse}

final case class NodesResponse(clusterName: ClusterName, responses: List[NodeResponse], failures: List[NodeError])
object NodesResponse {
  implicit object Encode extends Encoder[NodeResponse] {
    override def apply(response: NodeResponse): Json = {
      val configJson = response.loadedConfig
        .fold(createErrorResponseJson, createResponseJson)
      ResponseNodeJson(response.nodeId, configJson).asJson
    }
    private def createErrorResponseJson(error: LoadedConfig.Error) = error match {
      case LoadedConfig.FileParsingError(message) => ResponseConfigJson('FileParsingError, message = message.some)
      case LoadedConfig.FileNotExist(path) => ResponseConfigJson('FileNotExist, path = path.some)
      case LoadedConfig.EsFileNotExist(path) => ResponseConfigJson('EsFileNotExist, path = path.some)
      case LoadedConfig.EsFileMalformed(path, message) => ResponseConfigJson('EsFileMalformed, message = message.some, path = path.some)
      case LoadedConfig.EsIndexConfigurationMalformed(message) => ResponseConfigJson('EsIndexConfigurationMalformed, message = message.some)
      case LoadedConfig.IndexParsingError(message) => ResponseConfigJson('IndexParsingError, message = message.some)
    }

    private def createResponseJson(loadedConfig: LoadedConfig[String]) = {
      loadedConfig match {
        case LoadedConfig.FileRecoveredConfig(value, cause) => createFileRecoveredResponse(value, cause)
        case LoadedConfig.ForcedFileConfig(value) => ResponseConfigJson('ForcedFileConfig, config = value.some)
        case LoadedConfig.IndexConfig(value) => ResponseConfigJson('IndexConfig, config = value.some)
      }
    }

    private def createFileRecoveredResponse(value: String, cause: Cause) = {
      cause match {
        case Inl(LoadedConfig.FileRecoveredConfig.IndexNotExist) => ResponseConfigJson('FileRecoveredConfig, config = value.some, cause = 'IndexNotExist.some)
        case Inr(Inl(LoadedConfig.FileRecoveredConfig.IndexUnknownStructure)) => ResponseConfigJson('FileRecoveredConfig, config = value.some, cause = 'IndexUnknownStructure.some)
        case Inr(Inr(Inl(LoadedConfig.IndexParsingError(message)))) => ResponseConfigJson('FileRecoveredConfig, message = message.some, config = value.some, cause = 'IndexParsingError.some)
        case Inr(Inr(Inr(_))) => throw new IllegalStateException(s"should be CNil, but $cause found")
      }
    }
    implicit private val encodeResponseConfigJson: Encoder[ResponseConfigJson] = semiauto.deriveEncoder
    implicit private val encodeResponseNodeJson: Encoder[ResponseNodeJson] = responseNodeJson => {
      JsonObject(
        "nodeId" -> responseNodeJson.nodeId.asJson
      ).asJson deepMerge responseNodeJson.responseConfigJson.asJson
    }

    private case class ResponseConfigJson(`type`: Symbol, message: Option[String] = None, path: Option[Path] = None, config: Option[String] = None, cause: Option[Symbol] = None)
    private case class ResponseNodeJson(nodeId: NodeId, responseConfigJson: ResponseConfigJson)
  }
  implicit class Ops(nodesResponse: NodesResponse){
    def toJson: String = nodesResponse.asJson.noSpaces
  }

  private def tupleNodeError(nodeError: NodeError) = (nodeError.nodeId.value, nodeError.detailedMessage)
  private def tupleNodesResponse(nodesResponse: NodesResponse)= (nodesResponse.clusterName, nodesResponse.responses, nodesResponse.failures)

  final case class ClusterName(value: String) extends AnyVal
  final case class NodeId(value: String) extends AnyVal
  final case class NodeResponse(nodeId: NodeId, loadedConfig: Either[LoadedConfig.Error, LoadedConfig[String]])
  final case class NodeError(nodeId: NodeId, detailedMessage: String)

  implicit private lazy val encodeClusterName: Encoder[ClusterName] = deriveUnwrappedEncoder
  implicit private lazy val encodeNodeId: Encoder[NodeId] = deriveUnwrappedEncoder
  implicit private lazy val encodeNodeError: Encoder[NodeError] = Encoder.forProduct2("nodeId","detailedMessage")(tupleNodeError)
  implicit private lazy val encodeSymbol: Encoder[Symbol] = Encoder.encodeString.contramap(_.name)
  implicit private lazy val encodeNodesResponse: Encoder[NodesResponse] = Encoder.forProduct3("clusterName", "responses", "failures")(tupleNodesResponse)

}

