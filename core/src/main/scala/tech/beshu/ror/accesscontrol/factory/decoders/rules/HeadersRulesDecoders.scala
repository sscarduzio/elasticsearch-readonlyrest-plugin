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

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.{HeadersAndRule, HeadersOrRule}
import tech.beshu.ror.accesscontrol.domain.{AccessRequirement, Header}
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.HeadersHelper.headerAccessRequirementFromString
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.utils.StringWiseSplitter._

object HeadersAndRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeStringLikeOrNonEmptySetE(headerAccessRequirementFromString)
    .map { requirements =>
      RuleWithVariableUsageDefinition.create(new HeadersAndRule(HeadersAndRule.Settings(requirements)))
    }
)

object HeadersOrRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeStringLikeOrNonEmptySetE(headerAccessRequirementFromString)
    .map { requirements =>
      RuleWithVariableUsageDefinition.create(new HeadersOrRule(HeadersOrRule.Settings(requirements)))
    }
)

private object HeadersHelper {
  def headerAccessRequirementFromString(value: String): Either[String, AccessRequirement[Header]] =
    value
      .toNonEmptyStringsTuple
      .left.map(_ => errorMessage(value))
      .flatMap { case (first, second) =>
        if(first.value.startsWith("~")) {
          NonEmptyString.unapply(first.value.substring(1)) match {
            case Some(name) => Right(AccessRequirement.MustBeAbsent(new Header(Name(name), second)))
            case None => Left(errorMessage(value))
          }
        } else {
          Right(AccessRequirement.MustBePresent(new Header(Name(first), second)))
        }
      }

  private def errorMessage(rawValue: String) = {
    s"Cannot convert $rawValue to header access requirement (format: name:value_pattern or ~name:value_pattern - name and value_pattern cannot be empty)"
  }
}