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

import io.circe.generic.extras.ConfiguredJsonCodec
import io.circe.{Codec, Decoder, Encoder}
import tech.beshu.ror.configuration.loader.{LoadedRorConfig, Path}


@ConfiguredJsonCodec
sealed trait LoadedConfigErrorDto

object LoadedConfigErrorDto {
  def create(error: LoadedRorConfig.Error): LoadedConfigErrorDto = error match {
    case o: LoadedRorConfig.FileParsingError => FileParsingErrorDTO.create(o)
    case o: LoadedRorConfig.FileNotExist => FileNotExistDTO.create(o)
    case o: LoadedRorConfig.EsFileNotExist => EsFileNotExistDTO.create(o)
    case o: LoadedRorConfig.EsFileMalformed => EsFileMalformedDTO.create(o)
    case o: LoadedRorConfig.IndexParsingError => IndexParsingErrorDTO.create(o)
    case _: LoadedRorConfig.IndexUnknownStructure.type => IndexUnknownStructureDTO
  }

  def fromDto(o: LoadedConfigErrorDto): LoadedRorConfig.Error = o match {
    case o: FileParsingErrorDTO => FileParsingErrorDTO.fromDto(o)
    case o: FileNotExistDTO => FileNotExistDTO.fromDto(o)
    case o: EsFileNotExistDTO => EsFileNotExistDTO.fromDto(o)
    case o: EsFileMalformedDTO => EsFileMalformedDTO.fromDto(o)
    case o: IndexParsingErrorDTO => IndexParsingErrorDTO.fromDto(o)
    case _: IndexUnknownStructureDTO.type => LoadedRorConfig.IndexUnknownStructure
  }

  final case class FileParsingErrorDTO(message: String) extends LoadedConfigErrorDto

  object FileParsingErrorDTO {
    def create(o: LoadedRorConfig.FileParsingError): FileParsingErrorDTO =
      new FileParsingErrorDTO(
        message = o.message,
      )

    def fromDto(o: FileParsingErrorDTO): LoadedRorConfig.FileParsingError = LoadedRorConfig.FileParsingError(
      message = o.message,
    )
    implicit class Ops(o: FileParsingErrorDTO) {
      implicit def fromDto: LoadedRorConfig.FileParsingError = FileParsingErrorDTO.fromDto(o)
    }
  }
  final case class FileNotExistDTO(path: String) extends LoadedConfigErrorDto
  object FileNotExistDTO {
    def create(o: LoadedRorConfig.FileNotExist): FileNotExistDTO =
      new FileNotExistDTO(
        path = o.path.value,
      )

    def fromDto(o: FileNotExistDTO): LoadedRorConfig.FileNotExist = LoadedRorConfig.FileNotExist(
      path = Path(o.path),
    )
    implicit class Ops(o: FileNotExistDTO) {
      implicit def fromDto: LoadedRorConfig.FileNotExist = FileNotExistDTO.fromDto(o)
    }
  }
  final case class EsFileNotExistDTO(path: String) extends LoadedConfigErrorDto
  object EsFileNotExistDTO {
    def create(o: LoadedRorConfig.EsFileNotExist): EsFileNotExistDTO =
      new EsFileNotExistDTO(
        path = o.path.value,
      )

    def fromDto(o: EsFileNotExistDTO): LoadedRorConfig.EsFileNotExist = LoadedRorConfig.EsFileNotExist(
      path = Path(o.path),
    )
    implicit class Ops(o: EsFileNotExistDTO) {
      implicit def fromDto: LoadedRorConfig.EsFileNotExist = EsFileNotExistDTO.fromDto(o)
    }
  }
  final case class EsFileMalformedDTO(path: String, message: String) extends LoadedConfigErrorDto
  object EsFileMalformedDTO {
    def create(o: LoadedRorConfig.EsFileMalformed): EsFileMalformedDTO =
      new EsFileMalformedDTO(
        path = o.path.value,
        message = o.message,
      )

    def fromDto(o: EsFileMalformedDTO): LoadedRorConfig.EsFileMalformed = LoadedRorConfig.EsFileMalformed(
      path = Path(o.path),
      message = o.message,
    )
    implicit class Ops(o: EsFileMalformedDTO) {
      implicit def fromDto: LoadedRorConfig.EsFileMalformed = EsFileMalformedDTO.fromDto(o)
    }
  }
  final case class IndexParsingErrorDTO(message: String) extends LoadedConfigErrorDto
  object IndexParsingErrorDTO {
    def create(o: LoadedRorConfig.IndexParsingError): IndexParsingErrorDTO =
      new IndexParsingErrorDTO(
        message = o.message,
      )

    def fromDto(o: IndexParsingErrorDTO): LoadedRorConfig.IndexParsingError = LoadedRorConfig.IndexParsingError(
      message = o.message,
    )
    implicit class Ops(o: IndexParsingErrorDTO) {
      implicit def fromDto: LoadedRorConfig.IndexParsingError = IndexParsingErrorDTO.fromDto(o)
    }
  }
  case object IndexUnknownStructureDTO extends LoadedConfigErrorDto {
    implicit lazy val codecIndexUnknownStructureDto: Codec[IndexUnknownStructureDTO.type] = {
      val enc: Encoder[IndexUnknownStructureDTO.type] = Encoder.encodeString.contramap(_ => "index_not_exist")
      val dec = Decoder.decodeString.map(_ => IndexUnknownStructureDTO)
      Codec.from(dec, enc)
    }
  }

}