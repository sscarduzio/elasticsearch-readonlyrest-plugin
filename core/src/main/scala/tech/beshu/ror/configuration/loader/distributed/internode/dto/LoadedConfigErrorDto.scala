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

import io.circe.{Codec, Decoder, Encoder}
import tech.beshu.ror.configuration.loader.LoadedRorConfig
import tech.beshu.ror.utils.CirceOps.*

import java.nio.file.Paths

sealed trait LoadedConfigErrorDto

object LoadedConfigErrorDto {

  implicit val codec: Codec[LoadedConfigErrorDto] = codecWithTypeDiscriminator(
    encode = {
      case dto: FileParsingErrorDTO =>
        derivedEncoderWithType[FileParsingErrorDTO]("FileParsingErrorDTO")(dto)
      case dto: FileNotExistDTO =>
        derivedEncoderWithType[FileNotExistDTO]("FileNotExistDTO")(dto)
      case dto: EsFileNotExistDTO =>
        derivedEncoderWithType[EsFileNotExistDTO]("EsFileNotExistDTO")(dto)
      case dto: EsFileMalformedDTO =>
        derivedEncoderWithType[EsFileMalformedDTO]("EsFileMalformedDTO")(dto)
      case dto: CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO =>
        derivedEncoderWithType[CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO]("CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO")(dto)
      case dto: IndexParsingErrorDTO =>
        derivedEncoderWithType[IndexParsingErrorDTO]("FileParsingErrorDTO")(dto)
      case IndexUnknownStructureDTO =>
        derivedEncoderWithType[IndexUnknownStructureDTO.type]("IndexUnknownStructureDTO")(IndexUnknownStructureDTO)
    },
    decoders = Map(
      "FileParsingErrorDTO" -> derivedDecoderOfSubtype[LoadedConfigErrorDto, FileParsingErrorDTO],
      "FileNotExistDTO" -> derivedDecoderOfSubtype[LoadedConfigErrorDto, FileNotExistDTO],
      "EsFileNotExistDTO" -> derivedDecoderOfSubtype[LoadedConfigErrorDto, EsFileNotExistDTO],
      "EsFileMalformedDTO" -> derivedDecoderOfSubtype[LoadedConfigErrorDto, EsFileMalformedDTO],
      "CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO" -> derivedDecoderOfSubtype[LoadedConfigErrorDto, CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO],
      "FileParsingErrorDTO" -> derivedDecoderOfSubtype[LoadedConfigErrorDto, FileParsingErrorDTO],
      "IndexUnknownStructureDTO" -> derivedDecoderOfSubtype[LoadedConfigErrorDto, IndexUnknownStructureDTO.type],
    )
  )

  def create(error: LoadedRorConfig.Error): LoadedConfigErrorDto = error match {
    case o: LoadedRorConfig.FileParsingError => FileParsingErrorDTO.create(o)
    case o: LoadedRorConfig.FileNotExist => FileNotExistDTO.create(o)
    case o: LoadedRorConfig.EsFileNotExist => EsFileNotExistDTO.create(o)
    case o: LoadedRorConfig.EsFileMalformed => EsFileMalformedDTO.create(o)
    case o: LoadedRorConfig.CannotUseRorConfigurationWhenXpackSecurityIsEnabled =>
      CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO.create(o)
    case o: LoadedRorConfig.IndexParsingError => IndexParsingErrorDTO.create(o)
    case _: LoadedRorConfig.IndexUnknownStructure.type => IndexUnknownStructureDTO
    case _: LoadedRorConfig.IndexNotExist.type => IndexUnknownStructureDTO
  }

  def fromDto(o: LoadedConfigErrorDto): LoadedRorConfig.Error = o match {
    case o: FileParsingErrorDTO => FileParsingErrorDTO.fromDto(o)
    case o: FileNotExistDTO => FileNotExistDTO.fromDto(o)
    case o: EsFileNotExistDTO => EsFileNotExistDTO.fromDto(o)
    case o: EsFileMalformedDTO => EsFileMalformedDTO.fromDto(o)
    case o: CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO =>
      CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO.fromDto(o)
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
        path = o.path.toString,
      )

    def fromDto(o: FileNotExistDTO): LoadedRorConfig.FileNotExist = LoadedRorConfig.FileNotExist(
      path = Paths.get(o.path),
    )
    implicit class Ops(o: FileNotExistDTO) {
      implicit def fromDto: LoadedRorConfig.FileNotExist = FileNotExistDTO.fromDto(o)
    }
  }

  final case class EsFileNotExistDTO(path: String) extends LoadedConfigErrorDto
  object EsFileNotExistDTO {
    def create(o: LoadedRorConfig.EsFileNotExist): EsFileNotExistDTO =
      new EsFileNotExistDTO(
        path = o.path.toString,
      )

    def fromDto(o: EsFileNotExistDTO): LoadedRorConfig.EsFileNotExist = LoadedRorConfig.EsFileNotExist(
      path = Paths.get(o.path),
    )
    implicit class Ops(o: EsFileNotExistDTO) {
      implicit def fromDto: LoadedRorConfig.EsFileNotExist = EsFileNotExistDTO.fromDto(o)
    }
  }

  final case class EsFileMalformedDTO(path: String, message: String) extends LoadedConfigErrorDto
  object EsFileMalformedDTO {
    def create(o: LoadedRorConfig.EsFileMalformed): EsFileMalformedDTO =
      new EsFileMalformedDTO(
        path = o.path.toString,
        message = o.message,
      )

    def fromDto(o: EsFileMalformedDTO): LoadedRorConfig.EsFileMalformed = LoadedRorConfig.EsFileMalformed(
      path = Paths.get(o.path),
      message = o.message,
    )
    implicit class Ops(o: EsFileMalformedDTO) {
      implicit def fromDto: LoadedRorConfig.EsFileMalformed = EsFileMalformedDTO.fromDto(o)
    }
  }

  final case class CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO(typeOfConfiguration: String) extends LoadedConfigErrorDto
  object CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO {
    def create(o: LoadedRorConfig.CannotUseRorConfigurationWhenXpackSecurityIsEnabled): CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO =
      new CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO(
        typeOfConfiguration = o.typeOfConfiguration
      )

    def fromDto(o: CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO): LoadedRorConfig.CannotUseRorConfigurationWhenXpackSecurityIsEnabled =
      LoadedRorConfig.CannotUseRorConfigurationWhenXpackSecurityIsEnabled(
        typeOfConfiguration = o.typeOfConfiguration
      )
    implicit class Ops(o: CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO) {
      implicit def fromDto: LoadedRorConfig.CannotUseRorConfigurationWhenXpackSecurityIsEnabled =
        CannotUseRorConfigurationWhenXpackSecurityIsEnabledDTO.fromDto(o)
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