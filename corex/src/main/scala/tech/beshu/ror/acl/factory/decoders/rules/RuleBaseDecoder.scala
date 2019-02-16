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
package tech.beshu.ror.acl.factory.decoders.rules

import cats.data.NonEmptySet
import io.circe.CursorOp.DownField
import io.circe.Decoder.Result
import io.circe.{ACursor, Decoder, HCursor}
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.utils.CirceOps.DecodingFailureOps

sealed abstract class RuleBaseDecoder[T <: Rule](val associatedFields: Set[String])
  extends Decoder[T] {
  def decode(value: ACursor, associatedFieldsJson: ACursor): Decoder.Result[T] = {
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

  protected def doDecode(value: ACursor, associatedFieldsJson: ACursor): Decoder.Result[T]
}

object RuleBaseDecoder {
  private [decoders] class RuleDecoderWithoutAssociatedFields[T <: Rule](decoder: Decoder[T])
    extends RuleBaseDecoder[T](Set.empty) {
    override def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[T] = decoder.tryDecode(value)
    override def apply(c: HCursor): Result[T] = decoder.apply(c)
  }

  private [decoders] class RuleDecoderWithAssociatedFields[T <: Rule, S](ruleDecoderCreator: S => Decoder[T],
                                                                         associatedFields: NonEmptySet[String],
                                                                         associatedFieldsDecoder: Decoder[S])
    extends RuleBaseDecoder[T](associatedFields.toSortedSet) {
    override def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[T] = {
      for {
        decodedAssociatedFields <- associatedFieldsDecoder.tryDecode(associatedFieldsJson)
        rule <- ruleDecoderCreator(decodedAssociatedFields).tryDecode(value)
      } yield rule
    }

    override def apply(c: HCursor): Result[T] =
      Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message("Rule with associated fields decoding failed"))))
  }

  private[decoders] def failed[T <: Rule](error: RulesLevelCreationError): RuleBaseDecoder[T] =
    new RuleBaseDecoder[T](Set.empty) {
      private val decodingFailureResult = Left(DecodingFailureOps.fromError(error))
      override def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[T] = decodingFailureResult
      override def apply(c: HCursor): Result[T] = decodingFailureResult
    }
}
