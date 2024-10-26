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
package tech.beshu.ror.utils

import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax.EncoderOps
import tech.beshu.ror.implicits.*

import scala.language.implicitConversions

object CirceOps {
  implicit class CirceErrorOps(val error: io.circe.Error) extends AnyVal {
    def getPrettyMessage: String = {
      error match {
        case _: ParsingFailure =>
          s"Could not parse JSON. Cause: [${error.getMessage.show}]"
        case ex: DecodingFailure =>
          s"Could not parse at ${CursorOp.opsToPath(ex.history).show}: [${ex.getMessage.show}]"
      }
    }
  }

  implicit def toCirceErrorOps(error: io.circe.Error): CirceErrorOps = new CirceErrorOps(error)

  inline def derivedEncoderWithType[T](typeValue: String)(using inline t: scala.deriving.Mirror.Of[T]): Encoder[T] = {
    deriveEncoder[T].mapJson(_.deepMerge(Json.obj(("type", typeValue.asJson))))
  }

  inline def derivedDecoderOfSubtype[T, TT <: T](using inline t: scala.deriving.Mirror.Of[TT]): Decoder[T] = {
    Decoder.instance[T](c => deriveDecoder[TT].decodeJson(c.value))
  }

  def codecWithTypeDiscriminator[T](encode: T => Json, decoders: Map[String, Decoder[T]]): Codec[T] = {
    Codec.from[T](
      Decoder.instance { c =>
        c.downField("type").as[String] match
          case Right(typeValue) => decoders.get(typeValue) match
            case Some(decoder) => decoder.decodeJson(c.value)
            case None => Left(DecodingFailure(s"Missing decoder for type ${typeValue.show}", Nil))
          case Left(error) => Left(error)
      },
      Encoder.instance(encode)
    )
  }

}
