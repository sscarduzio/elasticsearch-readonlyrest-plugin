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
package tech.beshu.ror.configuration.loader.distributed.internode.dto

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import tech.beshu.ror.configuration.loader.distributed.{NodeConfigRequest, Timeout}

final case class NodeConfigRequestDTO(nanos: Long)

object NodeConfigRequestDTO {
  implicit val codec: Codec[NodeConfigRequestDTO] = deriveCodec

  def create(o: NodeConfigRequest): NodeConfigRequestDTO =
    new NodeConfigRequestDTO(
      nanos = o.timeout.nanos,
    )

  def fromDto(o: NodeConfigRequestDTO): NodeConfigRequest = NodeConfigRequest(
    timeout = Timeout(o.nanos),
  )
  implicit class Ops(o: NodeConfigRequestDTO) {
    implicit def fromDto: NodeConfigRequest = NodeConfigRequestDTO.fromDto(o)
  }
}