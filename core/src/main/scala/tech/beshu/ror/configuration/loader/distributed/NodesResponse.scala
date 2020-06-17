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
import io.circe.generic.extras.codec.UnwrappedCodec
import io.circe.syntax._
import io.circe.{Encoder, Json}
import shapeless._
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.configuration.loader.LoadedConfig.FileRecoveredConfig
import tech.beshu.ror.configuration.loader.distributed.NodesResponse.{ClusterName, NodeError, NodeResponse}
import tech.beshu.ror.configuration.loader.distributed.Summary.{ConfigurationStatement, Summary}
import tech.beshu.ror.configuration.loader.{LoadedConfig, Path}

final case class NodesResponse private(clusterName: ClusterName, responses: List[NodeResponse], failures: List[NodeError], summary: Summary)

object NodesResponse {
  def create(clusterName: ClusterName,
             responses: List[NodeResponse],
             failures: List[NodeError],
            ): NodesResponse = NodesResponse(clusterName, responses, failures, Summary.create(responses))
  implicit object EncodeErrorOrLoadedConfig extends Encoder[Either[LoadedConfig.Error, LoadedConfig[String]]] {
    override def apply(a: Either[LoadedConfig.Error, LoadedConfig[String]]): Json = encodeResponseConfigJson(toResponseConfigJson(a))

    private def toResponseConfigJson(config: Either[LoadedConfig.Error, LoadedConfig[String]]) =
      config
        .fold(createErrorResponseJson, createResponseJson)

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
        case LoadedConfig.FileRecoveredConfig(value, cause) =>
          cause.zipConst(value).fold(createFileRecoveredResponse)
        case LoadedConfig.ForcedFileConfig(value) => ResponseConfigJson('ForcedFileConfig, config = value.some)
        case LoadedConfig.IndexConfig(index, value) => ResponseConfigJson('IndexConfig, config = value.some, indexName = index.index.some)
      }
    }
    private object createFileRecoveredResponse extends Poly1 {
      type Out = ResponseConfigJson
      implicit val caseIndexNotExist: this.Case.Aux[(FileRecoveredConfig.IndexNotExist.type, String), Out] =
        at { case (_, config) => ResponseConfigJson('FileRecoveredConfig, config = config.some, cause = 'IndexNotExist.some) }
      implicit val caseIndexUnknownStructure: this.Case.Aux[(FileRecoveredConfig.IndexUnknownStructure.type, String), Out] =
        at { case (_, config) => ResponseConfigJson('FileRecoveredConfig, config = config.some, cause = 'IndexUnknownStructure.some) }
      implicit val caseIndexParsingError: this.Case.Aux[(LoadedConfig.IndexParsingError, String), Out] =
        at { case (cause, config) => ResponseConfigJson('FileRecoveredConfig, message = cause.message.some, config = config.some, cause = 'IndexParsingError.some) }
    }

    implicit private val encodeResponseConfigJson: Encoder[ResponseConfigJson] = io.circe.generic.semiauto.deriveEncoder
    private case class ResponseConfigJson(`type`: Symbol, message: Option[String] = None, path: Option[Path] = None, config: Option[String] = None, cause: Option[Symbol] = None, indexName: Option[IndexName] = None)
  }

  implicit class Ops(nodesResponse: NodesResponse) {
    def toJson: String = nodesResponse.asJson.noSpaces
  }

  private def tupleNodeError(nodeError: NodeError) = (nodeError.nodeId.value, nodeError.detailedMessage)

  private def tupleNodesResponse(nodesResponse: NodesResponse) = (nodesResponse.clusterName, nodesResponse.responses, nodesResponse.failures, nodesResponse.summary)

  final case class ClusterName(value: String) extends AnyVal
  final case class NodeId(value: String) extends AnyVal
  final case class NodeResponse(nodeId: NodeId, loadedConfig: Either[LoadedConfig.Error, LoadedConfig[String]])
  final case class NodeError(nodeId: NodeId, detailedMessage: String)

  implicit private lazy val encodeClusterName: Encoder[ClusterName] = UnwrappedCodec.codecForUnwrapped[ClusterName, String]
  implicit private lazy val encodeNodeId: Encoder[NodeId] = UnwrappedCodec.codecForUnwrapped[NodeId, String]
  implicit private lazy val encodeNodeError: Encoder[NodeError] = Encoder.forProduct2("nodeId", "detailedMessage")(tupleNodeError)
  implicit private lazy val encodeSymbol: Encoder[Symbol] = Encoder.encodeString.contramap(_.name)
  implicit private lazy val encodeConfigurationStatement: Encoder[ConfigurationStatement] = Encoder.forProduct2("nodes", "config")(s => (s.nodes, s.config))
  implicit private lazy val encodeSummary: Encoder[Summary] = Encoder.forProduct2("type", "value") {
    case Summary.ClearResult(config) => ("clear_result", config.asRight[LoadedConfig.Error].asJson)
    case Summary.AmbiguousConfigs(configs) => ("ambiguous_configs", configs.toList.asJson)
    case Summary.NoResult => ("no_result", ().asJson)
  }
  implicit private lazy val encodeNodeResponse: Encoder[NodeResponse] = response => {
    Json.obj(  "nodeId" -> response.nodeId.asJson)
      .deepMerge(response.loadedConfig.asJson)
  }
  implicit private lazy val encodeNodesResponse: Encoder[NodesResponse] = Encoder.forProduct4("clusterName", "responses", "failures", "summary")(tupleNodesResponse)
}

