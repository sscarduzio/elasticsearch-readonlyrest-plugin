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

