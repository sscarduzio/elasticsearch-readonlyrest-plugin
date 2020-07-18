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

import cats.data.NonEmptyList
import tech.beshu.ror.configuration.loader.LoadedConfig
import tech.beshu.ror.configuration.loader.distributed.NodesResponse.{NodeId, NodeResponse}
import cats.implicits._
import language.postfixOps

object Summary {
  sealed trait Error
  case object NoCurrentNodeResponse extends Error
  final case class CurrentNodeConfigError(error: LoadedConfig.Error) extends Error
  sealed trait Warning
  final case class NodeReturnedError(nodeId: NodeId, error: LoadedConfig.Error) extends Warning
  final case class NodeForcedFileConfig(nodeId: NodeId) extends Warning
  final case class NodeReturnedDifferentConfig(nodeId: NodeId, loadedConfig: LoadedConfig[String]) extends Warning
  final case class Result(config: LoadedConfig[String], warnings: List[Warning])


  def create2(currentNodeId: NodeId, nodesResponses: List[NodeResponse]): Either[Error, Result] = {
    findCurrentNodeResponse(currentNodeId, nodesResponses) match {
      case Some(NodeResponse(_, Right(loadedConfig))) =>
        val warnings = createWarnings(nodesResponses, loadedConfig)
        Result(loadedConfig, warnings) asRight
      case Some(NodeResponse(_, Left(error))) => CurrentNodeConfigError(error) asLeft
      case None => NoCurrentNodeResponse asLeft
    }
  }

  private def createWarnings(nodesResponses: List[NodeResponse], loadedConfig: LoadedConfig[String]) = {
    createNodeErrorWarnings(nodesResponses) ++
      createNodeNodeForcedFileConfigWarnings(nodesResponses) ++
      createNodeReturnedDifferentConfigWarnings(loadedConfig, nodesResponses)
  }

  private def findCurrentNodeResponse(currentNodeId: NodeId, nodesResponses: List[NodeResponse]) =
    nodesResponses.find(_.nodeId === currentNodeId)

  private def createNodeErrorWarnings(otherResponses: List[NodeResponse]): List[NodeReturnedError] =
    otherResponses.flatMap {
      case NodeResponse(nodeId, Left(error)) => NodeReturnedError(nodeId, error) :: Nil
      case _ => Nil
    }

  private def createNodeNodeForcedFileConfigWarnings(otherResponses: List[NodeResponse]): List[NodeForcedFileConfig] =
    otherResponses.flatMap {
      case NodeResponse(nodeId, Right(LoadedConfig.ForcedFileConfig(config))) => NodeForcedFileConfig(nodeId) :: Nil
      case _ => Nil
    }

  private def createNodeReturnedDifferentConfigWarnings(currentNodeConfig: LoadedConfig[String], otherResponses: List[NodeResponse]): List[NodeReturnedDifferentConfig] = {
    otherResponses.foldLeft(List.empty[NodeReturnedDifferentConfig]) {
      case (warnings, NodeResponse(_, Right(`currentNodeConfig`))) => warnings
      case (warnings, NodeResponse(nodeId, Right(loadedConfig))) => NodeReturnedDifferentConfig(nodeId, loadedConfig) :: warnings
      case (warnings, _) => warnings
    }
  }

  def create(nodesResponses: List[NodeResponse]): Summary = {
    nodesResponses.foldMap(createConfigurationMap).toList match {
      case Nil => NoResult
      case (Right(conf), _) :: Nil => ClearResult(conf)
      case head :: tail =>
        val results = NonEmptyList.of(head, tail: _*).map(r => ConfigurationStatement(r._2, r._1))
        AmbiguousConfigs(results)
    }
  }

  private def createConfigurationMap(nodeResponse: NodeResponse) =
    Map(nodeResponse.loadedConfig -> List(nodeResponse.nodeId))

  final case class ClearResult(config: LoadedConfig[String]) extends Summary
  final case class AmbiguousConfigs(configs: NonEmptyList[ConfigurationStatement]) extends Summary
  case object NoResult extends Summary
  sealed trait Summary
  final case class ConfigurationStatement(nodes: List[NodeId], config: Either[LoadedConfig.Error, LoadedConfig[String]])
}

