package tech.beshu.ror.acl.factory.decoders.definitions

import cats.implicits._
import io.circe.Decoder.Result
import io.circe.{Decoder, HCursor}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, _}
import tech.beshu.ror.acl.utils.ScalaExt._

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
