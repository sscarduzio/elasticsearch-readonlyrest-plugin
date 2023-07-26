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
package tech.beshu.ror.accesscontrol.blocks.variables.runtime

import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json.Folder
import io.circe.{Json, JsonNumber, JsonObject}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.{Convertible, Unresolvable}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator.CreationError
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.Json.JsonValue._
import tech.beshu.ror.accesscontrol.domain.Json._

object ResolvableJsonRepresentationOps {

  implicit class CreateTree(val jsonRepresentation: JsonRepresentation) extends AnyVal {

    def toResolvable(implicit variableCreator: RuntimeResolvableVariableCreator): Either[CreationError, ResolvableJsonRepresentation] = {
      mapTree(jsonRepresentation)
    }

    // not stack safe ATM (but it should not a big deal - can be improved in the future)
    private def mapTree(tree: JsonRepresentation)
                       (implicit variableCreator: RuntimeResolvableVariableCreator): Either[CreationError, ResolvableJsonRepresentation] = {
      tree match {
        case JsonTree.Object(fields) =>
          val (keys, mappedValuesResults) = fields.view.mapValues(mapTree).toList.unzip
          mappedValuesResults
            .sequence
            .map { mappedValues => keys.zip(mappedValues).toMap }
            .map(JsonTree.Object(_))
        case JsonTree.Array(elements) =>
          elements
            .map(mapTree)
            .sequence
            .map(JsonTree.Array(_))
        case JsonTree.Value(StringValue(value)) =>
          NonEmptyString.unapply(value) match {
            case Some(nonEmptyString) =>
              variableCreator
                .createSingleResolvableVariableFrom(nonEmptyString)
                .map(resolvableString =>
                  JsonTree.Value(resolvableString.map(StringValue))
                )
            case None =>
              Right(JsonTree.Value(AlreadyResolved.create(StringValue(value))))
          }
        case JsonTree.Value(NumValue(value)) =>
          Right(JsonTree.Value(AlreadyResolved.create(NumValue(value))))
        case JsonTree.Value(BooleanValue(value)) =>
          Right(JsonTree.Value(AlreadyResolved.create(BooleanValue(value))))
        case JsonTree.Value(NullValue) =>
          Right(JsonTree.Value(AlreadyResolved.create(NullValue)))
      }
    }
  }

  implicit class ResolveTree(val resolvableJson: ResolvableJsonRepresentation)
    extends AnyVal {

    def resolve(blockContext: BlockContext): Either[Unresolvable, JsonRepresentation] = {
      resolveTree(resolvableJson)(blockContext)
    }

    // not stack safe ATM (but it should not a big deal - can be improved in the future)
    private def resolveTree(tree: ResolvableJsonRepresentation): BlockContext => Either[Unresolvable, JsonRepresentation] = { blockContext =>
      tree match {
        case JsonTree.Object(fields) =>
          val (keys, mappedValuesResults) = fields.view.mapValues(resolveTree(_)(blockContext)).toList.unzip
          mappedValuesResults
            .sequence
            .map { mappedValues => keys.zip(mappedValues).toMap }
            .map(JsonTree.Object(_))
        case JsonTree.Array(elements) =>
          elements
            .map(resolveTree(_)(blockContext))
            .sequence
            .map(JsonTree.Array(_))
        case JsonTree.Value(value) =>
          value
            .resolve(blockContext)
            .map(JsonTree.Value(_))
      }
    }
  }

  implicit class FromCirceJson(val json: io.circe.Json) extends AnyVal {

    def toJsonRepresentation: JsonRepresentation = json.foldWith(JsonRepresentationCirceFolder)
  }

  private object JsonRepresentationCirceFolder extends Folder[JsonRepresentation] {
    override def onNull: JsonRepresentation =
      JsonTree.Value(NullValue)

    override def onBoolean(value: Boolean): JsonRepresentation =
      JsonTree.Value(BooleanValue(value))

    override def onNumber(value: JsonNumber): JsonRepresentation =
      JsonTree.Value(NumValue(value.toDouble))

    override def onString(value: String): JsonRepresentation =
      JsonTree.Value(StringValue(value))

    override def onArray(value: Vector[Json]): JsonRepresentation =
      JsonTree.Array(value.toList.map(_.foldWith(this)))

    override def onObject(value: JsonObject): JsonRepresentation =
      JsonTree.Object(value.toMap.view.mapValues(_.foldWith(this)).toMap)
  }

  private implicit val stringConvertible: Convertible[String] = new Convertible[String] {
    override def convert: String => Either[Convertible.ConvertError, String] = Right.apply
  }
}
