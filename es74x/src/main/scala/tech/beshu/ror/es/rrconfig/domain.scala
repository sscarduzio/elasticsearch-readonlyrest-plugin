package tech.beshu.ror.es.rrconfig

import shapeless._
import tech.beshu.ror.es.rrconfig.NodesResponse.{ClusterName, NodeError, NodeResponse}

sealed trait LoadedConfig
object LoadedConfig {
  sealed trait Error
  final case class FileRecoveredConfig(value: String, cause: FileRecoveredConfig.Cause) extends LoadedConfig
  object FileRecoveredConfig {
    type Cause = IndexNotExist.type :+: IndexUnknownStructure.type :+: IndexParsingError :+: CNil
    case object IndexNotExist
    case object IndexUnknownStructure
  }
  final case class ForcedFileConfig(value: String) extends LoadedConfig
  final case class IndexConfig(value: String) extends LoadedConfig
  final case class FileParsingError(message: String) extends LoadedConfig.Error
  final case class FileNotExist(path: Path) extends LoadedConfig.Error
  final case class EsFileNotExist(path: Path) extends LoadedConfig.Error
  final case class EsFileMalformed(path: Path, message: String) extends LoadedConfig.Error
  final case class EsIndexConfigurationMalformed(message: String) extends LoadedConfig.Error
  final case class IndexParsingError(message: String) extends LoadedConfig.Error
}
final case class Path(value: String) extends AnyVal

final case class NodeConfig(loadedConfig: Either[LoadedConfig.Error, LoadedConfig])
final case class Timeout(nanos:Long) extends AnyVal
final case class NodeConfigRequest(timeout: Timeout)


