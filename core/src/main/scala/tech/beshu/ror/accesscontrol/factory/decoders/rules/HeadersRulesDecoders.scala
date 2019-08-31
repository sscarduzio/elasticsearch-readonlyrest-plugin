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

import tech.beshu.ror.accesscontrol.blocks.rules.{HeadersAndRule, HeadersOrRule}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.factory.decoders.rules.HeadersHelper.headerFromString
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.utils.StringWiseSplitter._

object HeadersAndRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLikeOrNonEmptySetE(headerFromString).map(headers => new HeadersAndRule(HeadersAndRule.Settings(headers)))
)

object HeadersOrRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLikeOrNonEmptySetE(headerFromString).map(headers => new HeadersOrRule(HeadersOrRule.Settings(headers)))
)

private object HeadersHelper {
  def headerFromString(value: String): Either[String, Header] =
    value
      .toNonEmptyStringsTuple
      .map { case (first, second) => Header(Name(first), second) }
      .left.map(_ => s"Cannot convert $value to header")
}