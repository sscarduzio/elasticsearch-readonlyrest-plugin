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

import cats.implicits.*
import tech.beshu.ror.configuration.loader.LoadedRorConfig
import tech.beshu.ror.configuration.loader.distributed.NodesResponse.{NodeError, NodeId, NodeResponse}

import scala.language.postfixOps

object Summary {
  case object CurrentNodeHaveToProduceResult extends Exception

  sealed trait Error
  final case class CurrentNodeConfigError(error: LoadedRorConfig.Error) extends Error
  final case class CurrentNodeResponseError(detailedMessage: String) extends Error
  case object CurrentNodeResponseTimeoutError extends Error

  sealed trait Warning
  final case class NodeResponseTimeoutWarning(nodeId: NodeId) extends Warning
  final case class NodeReturnedConfigError(nodeId: NodeId, error: LoadedRorConfig.Error) extends Warning
  final case class NodeReturnedUnknownError(nodeId: NodeId, detailedMessage: String) extends Warning
  final case class NodeForcedFileConfig(nodeId: NodeId) extends Warning
  final case class NodeReturnedDifferentConfig(nodeId: NodeId, loadedConfig: LoadedRorConfig[String]) extends Warning
  final case class Result(config: LoadedRorConfig[String], warnings: List[Warning])


  def create(currentNodeId: NodeId, nodesResponses: List[NodeResponse], failures: List[NodeError]): Either[Error, Result] = {
    findCurrentNodeResponse(currentNodeId, nodesResponses) match {
      case Some(NodeResponse(_, Right(loadedConfig))) =>
        val warnings = createWarnings(nodesResponses, loadedConfig, failures)
        Result(loadedConfig, warnings) asRight
      case Some(NodeResponse(_, Left(error))) => CurrentNodeConfigError(error) asLeft
      case None => findCurrentNodeFailure(currentNodeId, failures) match {
        case Some(NodeError(_, cause)) => cause match {
          case NodeError.RorConfigActionNotFound => throw CurrentNodeHaveToProduceResult
          case NodeError.Timeout => CurrentNodeResponseTimeoutError asLeft
          case NodeError.Unknown(detailedMessage) => CurrentNodeResponseError(detailedMessage) asLeft
        }
        case None => throw CurrentNodeHaveToProduceResult
      }
    }
  }

  private def createWarnings(nodesResponses: List[NodeResponse],
                             loadedConfig: LoadedRorConfig[String],
                             failures: List[NodeError]): List[Warning] = {
    createNodeErrorWarnings(nodesResponses) ++
      createNodeNodeForcedFileConfigWarnings(nodesResponses) ++
      createNodeReturnedDifferentConfigWarnings(loadedConfig, nodesResponses) ++
      createNodeReturnedUnknownError(failures) ++
      createNodeResponseTimeoutWarnings(failures) ++
      Nil
  }

  private def findCurrentNodeFailure(currentNodeId: NodeId, nodesResponses: List[NodeError]) =
    nodesResponses.find(_.nodeId === currentNodeId)

  private def findCurrentNodeResponse(currentNodeId: NodeId, nodesResponses: List[NodeResponse]) =
    nodesResponses.find(_.nodeId === currentNodeId)

  private def createNodeReturnedUnknownError(failures: List[NodeError]): List[NodeReturnedUnknownError] =
    failures.flatMap {
      case NodeError(nodeId, NodeError.Unknown(detailedMessage)) => NodeReturnedUnknownError(nodeId, detailedMessage) :: Nil
      case _ => Nil
    }

  private def createNodeErrorWarnings(otherResponses: List[NodeResponse]): List[NodeReturnedConfigError] =
    otherResponses.flatMap {
      case NodeResponse(nodeId, Left(error)) => NodeReturnedConfigError(nodeId, error) :: Nil
      case _ => Nil
    }

  private def createNodeResponseTimeoutWarnings(failures: List[NodeError]): List[NodeResponseTimeoutWarning] =
    failures.flatMap {
      case NodeError(nodeId, NodeError.Timeout) => NodeResponseTimeoutWarning(nodeId) :: Nil
      case _ => Nil
    }

  private def createNodeNodeForcedFileConfigWarnings(otherResponses: List[NodeResponse]): List[NodeForcedFileConfig] =
    otherResponses.flatMap {
      case NodeResponse(nodeId, Right(LoadedRorConfig(_))) => NodeForcedFileConfig(nodeId) :: Nil
      case _ => Nil
    }

  private def createNodeReturnedDifferentConfigWarnings(currentNodeConfig: LoadedRorConfig[String],
                                                        otherResponses: List[NodeResponse]): List[NodeReturnedDifferentConfig] = {
    otherResponses.foldLeft(List.empty[NodeReturnedDifferentConfig]) {
      case (warnings, NodeResponse(_, Right(`currentNodeConfig`))) => warnings
      case (warnings, NodeResponse(nodeId, Right(loadedConfig))) => NodeReturnedDifferentConfig(nodeId, loadedConfig) :: warnings
      case (warnings, _) => warnings
    }
  }

}

