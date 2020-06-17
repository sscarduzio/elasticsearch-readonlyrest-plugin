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
package tech.beshu.ror.configuration.loader

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.generic.codec.DerivedAsObjectCodec
import io.circe.generic.extras.codec.UnwrappedCodec
import io.circe.shapes._
import io.circe.{Codec, Decoder, Encoder}
import shapeless.{:+:, Coproduct, HNil, LabelledGeneric, Lazy}
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.configuration.loader.LoadedConfig.FileRecoveredConfig.{IndexNotExist, IndexUnknownStructure}
import tech.beshu.ror.configuration.loader.LoadedConfig._

package object distributed {

  implicit lazy val codecNonEmptyString: Codec[NonEmptyString] = Codec.from(
    Decoder.decodeString.emap(NonEmptyString.from),
    Encoder.encodeString.contramap(_.value)
  )
  implicit lazy val codecIndexName: Codec[IndexName] = UnwrappedCodec.codecForUnwrapped[IndexName, NonEmptyString]
  implicit lazy val codecRorConfigurationIndex: Codec[RorConfigurationIndex] = UnwrappedCodec.codecForUnwrapped[RorConfigurationIndex, IndexName]
  implicit lazy val codecLoadedConfigError: Codec[LoadedConfig.Error] = deriveSealedTraitCodec
  implicit lazy val codecFileParsingError: Codec[FileParsingError] = Codec.forProduct1("message")(FileParsingError)(_.message)
  implicit lazy val codecFileNotExist: Codec[FileNotExist] = Codec.forProduct1("path")(FileNotExist)(_.path)
  implicit lazy val codecEsFileNotExist: Codec[EsFileNotExist] = Codec.forProduct1("path")(EsFileNotExist)(_.path)
  implicit lazy val codecEsFileMalformed: Codec[EsFileMalformed] = Codec.forProduct2("path", "message")(EsFileMalformed)(m => (m.path, m.message))
  implicit lazy val codecEsIndexConfigurationMalformed: Codec[EsIndexConfigurationMalformed] = Codec.forProduct1("message")(EsIndexConfigurationMalformed)(_.message)
  implicit lazy val codecIndexNotExist: Codec[IndexNotExist.type] = codecProduct0("index_not_exist")
  implicit lazy val codecIndexUnknownStructure: Codec[IndexUnknownStructure.type] = codecProduct0("index_not_exist")
  implicit lazy val codecIndexParsingError: Codec[IndexParsingError] = Codec.forProduct1("message")(IndexParsingError)(_.message)
  implicit lazy val codecFileRecoveredConfigCause: Codec[FileRecoveredConfig.Cause] = deriveReprCodec
  implicit lazy val codecFileRecoveredConfig: Codec[FileRecoveredConfig[String]] = Codec.forProduct2("value", "cause")(FileRecoveredConfig[String])(c => (c.value, c.cause))
  implicit lazy val codecForcedFileConfig: Codec[ForcedFileConfig[String]] = Codec.forProduct1("value")(ForcedFileConfig[String])(_.value)
  implicit lazy val codecIndexConfig: Codec[IndexConfig[String]] = Codec.forProduct2("indexName", "value")(IndexConfig[String])(c => (c.indexName, c.value))
  implicit lazy val codecLoadedConfig: Codec[LoadedConfig[String]] = deriveSealedTraitCodec
  implicit lazy val codecTimeout: Codec[Timeout] = UnwrappedCodec.codecForUnwrapped[Timeout, Long]
  implicit lazy val codecNodeConfigRequest: Codec[NodeConfigRequest] = Codec.forProduct1[NodeConfigRequest, Timeout]("timeout")(NodeConfigRequest(_))(_.timeout)
  implicit lazy val codecPath: Codec[Path] = UnwrappedCodec.codecForUnwrapped[Path, String]

  private def deriveReprCodec[H: Encoder : Decoder, T <: Coproduct : Encoder : Decoder]: Codec[H :+: T] =
    Codec.from(Decoder[H :+: T], Encoder[H :+: T])

  private def deriveSealedTraitCodec[A](implicit codec: Lazy[SealedTraitCodec[A]]): Codec[A] = codec.value.codec
  case class SealedTraitCodec[A](codec: Codec[A])
  object SealedTraitCodec {
    implicit def deriveSealedTraitCodec[A, R <: Coproduct](implicit gen: LabelledGeneric.Aux[A, R], codec: Lazy[DerivedAsObjectCodec[A]]): SealedTraitCodec[A] = SealedTraitCodec(codec.value)
  }
  private def codecProduct0[A](name: String)(implicit gen: LabelledGeneric.Aux[A, HNil]): Codec[A] = {
    val instance = gen.from(HNil)
    Codec.from(Decoder.decodeString.emap { case `name` => Right(instance);case invalid => Left(s"""Invalid value: "$invalid"""") }, Encoder.encodeString.contramap(_ => name))
  }
}
