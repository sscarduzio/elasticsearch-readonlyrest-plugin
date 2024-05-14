package tech.beshu.ror.configuration.loader.distributed.internode.dto

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

import cats.implicits.*
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import tech.beshu.ror.configuration.loader.distributed.NodeConfig

final case class NodeConfigDTO(loadedConfig: Either[LoadedConfigErrorDto, LoadedConfigDTO])

object NodeConfigDTO {

  implicit val codec: Codec[NodeConfigDTO] = deriveCodec

  def create(o: NodeConfig): NodeConfigDTO =
    new NodeConfigDTO(
      loadedConfig = o.loadedConfig.bimap(LoadedConfigErrorDto.create, LoadedConfigDTO.create),
    )

  def fromDto(o: NodeConfigDTO): NodeConfig = NodeConfig(
    loadedConfig = o.loadedConfig.bimap(LoadedConfigErrorDto.fromDto, LoadedConfigDTO.fromDto),
  )
  implicit class Ops(o: NodeConfigDTO) {
    implicit def fromDto: NodeConfig = NodeConfigDTO.fromDto(o)
  }
}