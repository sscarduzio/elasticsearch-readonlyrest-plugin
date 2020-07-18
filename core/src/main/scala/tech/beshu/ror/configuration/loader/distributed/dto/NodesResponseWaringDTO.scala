package tech.beshu.ror.configuration.loader.distributed.dto

import io.circe.generic.extras.ConfiguredJsonCodec
import tech.beshu.ror.configuration.loader.distributed.Summary
import tech.beshu.ror.configuration.loader.distributed.Summary.{NodeForcedFileConfig, NodeReturnedDifferentConfig, NodeReturnedError}

@ConfiguredJsonCodec
sealed trait NodesResponseWaringDTO
object NodesResponseWaringDTO {
  def create(warning: Summary.Warning): NodesResponseWaringDTO = warning match {
    case w: NodeReturnedError => NODE_RETURNED_ERROR.create(w)
    case w: Summary.NodeForcedFileConfig => NODE_FORCED_FILE_CONFIG.create(w)
    case w: Summary.NodeReturnedDifferentConfig => NODE_RETURNED_DIFFERENT_CONFIG.create(w)
  }
  final case class NODE_RETURNED_DIFFERENT_CONFIG(nodeId: String, loadedConfig: LoadedConfigDTO) extends NodesResponseWaringDTO
  object NODE_RETURNED_DIFFERENT_CONFIG {
    def create(o: NodeReturnedDifferentConfig): NODE_RETURNED_DIFFERENT_CONFIG =
      new NODE_RETURNED_DIFFERENT_CONFIG(
        nodeId = o.nodeId.value,
        loadedConfig = LoadedConfigDTO.create(o.loadedConfig),
      )
  }
  final case class NODE_FORCED_FILE_CONFIG(nodeId: String) extends NodesResponseWaringDTO
  object NODE_FORCED_FILE_CONFIG {
    def create(o: NodeForcedFileConfig): NODE_FORCED_FILE_CONFIG =
      new NODE_FORCED_FILE_CONFIG(
        nodeId = o.nodeId.value,
      )
  }
  object NODE_RETURNED_ERROR {

    def create(o: NodeReturnedError): NODE_RETURNED_ERROR =
      new NODE_RETURNED_ERROR(
        nodeId = o.nodeId.value,
        error = LoadedConfigError.createError(o.error),
      )
  }
  final case class NODE_RETURNED_ERROR(nodeId: String, error: String) extends NodesResponseWaringDTO
}



