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
import cats.kernel.{Monoid, Semigroup}
import tech.beshu.ror.configuration.loader.LoadedConfig
import tech.beshu.ror.configuration.loader.distributed.NodesResponse.{NodeId, NodeResponse}
import cats.implicits._
object Summary {
  def create(nodesResponses: List[NodeResponse]): Summary = {
    nodesResponses.foldMap(createConfigurationMap).toList match {
      case Nil => NoResult
      case (Right(conf), _) :: Nil => ClearResult(conf)
      case head :: tail =>
        val results= NonEmptyList.of(head, tail:_*).map(r => ConfigurationStatement(r._2, r._1))
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

