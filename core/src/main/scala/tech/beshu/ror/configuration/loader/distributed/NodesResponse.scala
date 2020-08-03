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

import io.circe.syntax._
import tech.beshu.ror.configuration.loader.LoadedConfig
import tech.beshu.ror.configuration.loader.distributed.dto.ResultDTO

final case class NodesResponse private(resultDTO: ResultDTO) extends AnyVal

object NodesResponse {
  def create(localNode: NodeId,
             responses: List[NodeResponse],
             failures: List[NodeError],
            ): NodesResponse =
    {
      NodesResponse(ResultDTO.create(Summary.create(localNode, responses, failures)))
    }

  implicit class Ops(nodesResponse: NodesResponse) {
    def toJson: String = nodesResponse.resultDTO.asJson.noSpaces
  }

  final case class NodeId(value: String) extends AnyVal
  final case class NodeResponse(nodeId: NodeId, loadedConfig: Either[LoadedConfig.Error, LoadedConfig[String]])
  final case class NodeError(nodeId: NodeId, cause: NodeError.Cause)
  object NodeError {
    sealed trait Cause
    case object ActionNotFound extends Cause
    case object Timeout extends Cause
    final case class Unknown(detailedMessage: String) extends Cause
  }
}



