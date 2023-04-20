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
package tech.beshu.ror.configuration.loader.external.dto

import cats.implicits._
import io.circe.generic.extras.{Configuration, ConfiguredJsonCodec}
import tech.beshu.ror.configuration.loader.distributed.Summary
import tech.beshu.ror.configuration.loader.distributed.Summary.{NodeForcedFileConfig, NodeReturnedConfigError, NodeReturnedDifferentConfig}

@ConfiguredJsonCodec
sealed trait NodesResponseWaringDTO
object NodesResponseWaringDTO {
  def create(warning: Summary.Warning): NodesResponseWaringDTO = warning match {
    case w: Summary.NodeReturnedConfigError => NODE_RETURNED_CONFIG_ERROR.create(w)
    case w: Summary.NodeReturnedUnknownError => NODE_RETURNED_UNKNOWN_ERROR.create(w)
    case w: Summary.NodeForcedFileConfig => NODE_FORCED_FILE_CONFIG.create(w)
    case w: Summary.NodeReturnedDifferentConfig => NODE_RETURNED_DIFFERENT_CONFIG.create(w)
    case w: Summary.NodeResponseTimeoutWarning => NODE_RESPONSE_TIMEOUT_ERROR.create(w)
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
  final case class NODE_RETURNED_CONFIG_ERROR(nodeId: String, error: String) extends NodesResponseWaringDTO
  object NODE_RETURNED_CONFIG_ERROR {
    def create(o: Summary.NodeReturnedConfigError): NODE_RETURNED_CONFIG_ERROR =
      new NODE_RETURNED_CONFIG_ERROR(
        nodeId = o.nodeId.value,
        error = o.error.show,
      )
  }
  final case class NODE_RETURNED_UNKNOWN_ERROR(nodeId: String, detailedMessage: String) extends NodesResponseWaringDTO
  object NODE_RETURNED_UNKNOWN_ERROR {
    def create(o: Summary.NodeReturnedUnknownError): NODE_RETURNED_UNKNOWN_ERROR =
      new NODE_RETURNED_UNKNOWN_ERROR(
        nodeId = o.nodeId.value,
        detailedMessage = o.detailedMessage,
      )
  }
  final case class NODE_RESPONSE_TIMEOUT_ERROR(nodeId: String) extends NodesResponseWaringDTO
  object NODE_RESPONSE_TIMEOUT_ERROR {
    def create(o: Summary.NodeResponseTimeoutWarning): NODE_RESPONSE_TIMEOUT_ERROR =
      new NODE_RESPONSE_TIMEOUT_ERROR(
        nodeId = o.nodeId.value,
      )
  }

  implicit val configuration: Configuration = Configuration.default.withDiscriminator("type")
}



