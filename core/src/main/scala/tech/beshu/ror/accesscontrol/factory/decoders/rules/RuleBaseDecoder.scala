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
package tech.beshu.ror.accesscontrol.factory.decoders.rules

import io.circe.CursorOp.DownField
import io.circe.Decoder.Result
import io.circe.{ACursor, Decoder, HCursor}
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule._
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.utils.CirceOps._

sealed abstract class RuleDecoder[T <: Rule : RuleName] extends Decoder[RuleDecoder.Result[T]] {

  def associatedFields: Set[String]

  def ruleName: Rule.Name = implicitly[RuleName[T]].name

  override def apply(c: HCursor): Decoder.Result[RuleDecoder.Result[T]] = {
    decode(
      c.downField(ruleName.value),
      c.withKeysOnly(associatedFields)
    ) map { ruleWithVariable =>
      RuleDecoder.Result(ruleWithVariable, c.withoutKeys(associatedFields + ruleName.value))
    }
  }

  private def decode(value: ACursor, associatedFieldsJson: ACursor): Decoder.Result[RuleDefinition[T]] = {
    doDecode(value, associatedFieldsJson)
      .left
      .map(df => df.overrideDefaultErrorWith(RulesLevelCreationError {
        value.up.focus match {
          case Some(json) =>
            MalformedValue(json)
          case None =>
            val ruleName = df.history.headOption.collect { case df: DownField => df.k }.getOrElse("")
            Message(s"Malformed rule $ruleName")
        }
      }))
  }

  protected def doDecode(value: ACursor, associatedFieldsJson: ACursor): Decoder.Result[RuleDefinition[T]]

}
object RuleDecoder {
  final case class Result[T <: Rule](rule: RuleDefinition[T], unconsumedCursor: ACursor)
}

object RuleBaseDecoder {

  abstract class RuleBaseDecoderWithoutAssociatedFields[T <: Rule : RuleName] extends RuleDecoder[T] {

    protected def decoder: Decoder[RuleDefinition[T]]

    override val associatedFields: Set[String] = Set.empty

    override def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[RuleDefinition[T]] =
      decoder.tryDecode(value)
  }

  abstract class RuleBaseDecoderWithAssociatedFields[T <: Rule : RuleName, S] extends RuleDecoder[T] {

    def ruleDecoderCreator: S => Decoder[RuleDefinition[T]]

    def associatedFieldsDecoder: Decoder[S]

    override def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[RuleDefinition[T]] = {
      for {
        decodedAssociatedFields <- associatedFieldsDecoder.tryDecode(associatedFieldsJson)
        rule <- ruleDecoderCreator(decodedAssociatedFields).tryDecode(value)
      } yield rule
    }
  }
}
