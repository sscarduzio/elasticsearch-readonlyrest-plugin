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

import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.DataStreamsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.DataStreamName
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.DataStreamsDecodersHelper._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._

object DataStreamsRuleDecoder
  extends RuleBaseDecoderWithoutAssociatedFields[DataStreamsRule] {

  override protected def decoder: Decoder[RuleDefinition[DataStreamsRule]] =
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[DataStreamName]]
      .toSyncDecoder
      .emapE { dataStreams =>
        if (checkIfAlreadyResolvedDataStreamVariableContains(dataStreams, DataStreamName.all))
          Left(RulesLevelCreationError(Message(s"Setting up a rule (${DataStreamsRule.Name.show}) that matches all the values is redundant - data stream ${DataStreamName.all.show}")))
        else if (checkIfAlreadyResolvedDataStreamVariableContains(dataStreams, DataStreamName.wildcard))
          Left(RulesLevelCreationError(Message(s"Setting up a rule (${DataStreamsRule.Name.show}) that matches all the values is redundant - data stream ${DataStreamName.wildcard.show}")))
        else
          Right(dataStreams)
      }
      .map(dataStreams => RuleDefinition.create(new DataStreamsRule(DataStreamsRule.Settings(dataStreams))))
      .decoder
}

private object DataStreamsDecodersHelper {
  private implicit val dataStreamNameConvertible: Convertible[DataStreamName] = new Convertible[DataStreamName] {
    override def convert: String => Either[Convertible.ConvertError, DataStreamName] = { str =>

      for {
        dataStreamName <- DataStreamName.fromString(str).toRight(Convertible.ConvertError("Data stream name cannot be empty"))
        _ <- validateIsLowerCase(dataStreamName)
      } yield dataStreamName
    }

    private def validateIsLowerCase(value: DataStreamName) = value match {
      case DataStreamName.Full(name) if isLowerCase(name) => Right(())
      case DataStreamName.Pattern(name) if isLowerCase(name) => Right(())
      case DataStreamName.Full(_) | DataStreamName.Pattern(_) =>
        Left(Convertible.ConvertError("Data stream name cannot contain the upper case characters"))
      case DataStreamName.All => Right(())
      case DataStreamName.Wildcard => Right(())
    }

    private def isLowerCase(value: NonEmptyString) = value.value.toLowerCase == value.value
  }

  implicit val dataStreamNameValueDecoder: Decoder[RuntimeMultiResolvableVariable[DataStreamName]] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .toSyncDecoder
      .emapE { str =>
        RuntimeResolvableVariableCreator
          .createMultiResolvableVariableFrom[DataStreamName](str)
          .left.map(error => RulesLevelCreationError(Message(error.show)))
      }
      .decoder

  private[rules] def checkIfAlreadyResolvedDataStreamVariableContains(dataStreamsVars: NonEmptySet[RuntimeMultiResolvableVariable[DataStreamName]],
                                                                      dataStream: DataStreamName): Boolean = {
    dataStreamsVars
      .find {
        case AlreadyResolved(dataStreams) => dataStreams.contains_(dataStream)
        case ToBeResolved(_) => false
      }
      .isDefined
  }
}