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
package tech.beshu.ror.accesscontrol.factory.decoders.definitions

import cats.implicits._
import cats.Applicative
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.accesscontrol.utils.ADecoder
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}
import tech.beshu.ror.utils.ScalaOps._

import scala.language.higherKinds

object DefinitionsBaseDecoder {

  def instance[F[_] : Applicative, A <: Item](definitionsSectionName: String)
                                             (implicit decoder: ADecoder[F, A]): ADecoder[F, Definitions[A]] = {
    DecoderHelpers
      .decodeFieldList[A, F](definitionsSectionName, DefinitionsLevelCreationError.apply)
      .emapE {
        case NoField =>
          Right(Definitions(List.empty[A]))
        case FieldListValue(Nil) =>
          Left(DefinitionsLevelCreationError(Message(s"$definitionsSectionName declared, but no definition found")))
        case FieldListValue(list) =>
          list.findDuplicates(_.id) match {
            case Nil =>
              Right(Definitions(list))
            case duplicates =>
              Left(DefinitionsLevelCreationError(Message(
                s"$definitionsSectionName definitions must have unique identifiers. Duplicates: ${duplicates.map(showId).mkString(",")}"
              )))
          }
      }
  }

  private def showId(item: Item): String = {
    implicit val _ = item.show
    item.id.show
  }
}
