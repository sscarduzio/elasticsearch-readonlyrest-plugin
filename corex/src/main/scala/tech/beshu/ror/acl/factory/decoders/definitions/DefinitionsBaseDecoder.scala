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
package tech.beshu.ror.acl.factory.decoders.definitions

import cats.implicits._
import io.circe.Decoder.Result
import io.circe.{Decoder, HCursor}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, _}
import tech.beshu.ror.acl.utils.ScalaOps._

abstract class DefinitionsBaseDecoder[T <: Item : Decoder](definitionsSectionName: String)
  extends Decoder[Definitions[T]] {

  override def apply(value: HCursor): Result[Definitions[T]] =
    definitionsDecoder.tryDecode(value)

  private val definitionsDecoder =
    DecoderHelpers
      .decodeFieldList[T](definitionsSectionName, DefinitionsLevelCreationError.apply)
      .emapE {
        case NoField =>
          Right(Definitions(Set.empty[T]))
        case FieldListValue(Nil) =>
          Left(DefinitionsLevelCreationError(Message(s"$definitionsSectionName declared, but no definition found")))
        case FieldListValue(list) =>
          list.findDuplicates(_.id) match {
            case Nil =>
              Right(Definitions(list.toSet))
            case duplicates =>
              Left(DefinitionsLevelCreationError(Message(
                s"$definitionsSectionName definitions must have unique identifiers. Duplicates: ${duplicates.map(showId).mkString(",")}"
              )))
          }
      }

  private def showId(item: Item): String = {
    implicit val _ = item.show
    item.id.show
  }
}
