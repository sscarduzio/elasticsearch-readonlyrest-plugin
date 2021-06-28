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

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.generic.extras.ConfiguredJsonCodec
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorConfigurationIndex}
import tech.beshu.ror.configuration.loader.LoadedRorConfig

@ConfiguredJsonCodec
sealed trait LoadedConfigDTO

object LoadedConfigDTO {
  def create(o: LoadedRorConfig[String]): LoadedConfigDTO = o match {
    case o: LoadedRorConfig.FileConfig[String] => FileConfigDTO.create(o)
    case o: LoadedRorConfig.ForcedFileConfig[String] => ForcedFileConfigDTO.create(o)
    case o: LoadedRorConfig.IndexConfig[String] => IndexConfigDTO.create(o)
  }

  def fromDto(o: LoadedConfigDTO): LoadedRorConfig[String] = o match {
    case o: IndexConfigDTO => IndexConfigDTO.fromDto(o)
    case o: FileConfigDTO => FileConfigDTO.fromDto(o)
    case o: ForcedFileConfigDTO => ForcedFileConfigDTO.fromDto(o)
  }

  final case class IndexConfigDTO(indexName: String, value: String) extends LoadedConfigDTO
  object IndexConfigDTO {
    def create(o: LoadedRorConfig.IndexConfig[String]): IndexConfigDTO =
      new IndexConfigDTO(
        indexName = o.indexName.index.name.value,
        value = o.value,
      )

    def fromDto(o: IndexConfigDTO): LoadedRorConfig.IndexConfig[String] = LoadedRorConfig.IndexConfig(
      indexName = RorConfigurationIndex(IndexName.Full(NonEmptyString.unsafeFrom(o.indexName))),
      value = o.value,
    )
    implicit class Ops(o: IndexConfigDTO) {
      implicit def fromDto: LoadedRorConfig.IndexConfig[String] = IndexConfigDTO.fromDto(o)
    }
  }
  final case class FileConfigDTO(value: String) extends LoadedConfigDTO
  object FileConfigDTO {
    def create(o: LoadedRorConfig.FileConfig[String]): FileConfigDTO =
      new FileConfigDTO(
        value = o.value,
      )

    def fromDto(o: FileConfigDTO): LoadedRorConfig.FileConfig[String] = LoadedRorConfig.FileConfig(
      value = o.value,
    )
    implicit class Ops(o: FileConfigDTO) {
      implicit def fromDto: LoadedRorConfig.FileConfig[String] = FileConfigDTO.fromDto(o)
    }
  }
  final case class ForcedFileConfigDTO(value: String) extends LoadedConfigDTO
  object ForcedFileConfigDTO {
    def create(o: LoadedRorConfig.ForcedFileConfig[String]): ForcedFileConfigDTO =
      new ForcedFileConfigDTO(
        value = o.value,
      )

    def fromDto(o: ForcedFileConfigDTO): LoadedRorConfig.ForcedFileConfig[String] = LoadedRorConfig.ForcedFileConfig(
      value = o.value,
    )
    implicit class Ops(o: ForcedFileConfigDTO) {
      implicit def fromDto: LoadedRorConfig.ForcedFileConfig[String] = ForcedFileConfigDTO.fromDto(o)
    }
  }
}
