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
package tech.beshu.ror.acl.utils

import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}

import scala.language.implicitConversions

class RuleResultOps(val ruleResult: RuleResult) extends AnyVal {

  def toEither: Either[Rejected, Fulfilled] = ruleResult match {
    case fulfilled: Fulfilled => Right(fulfilled)
    case rejected: RuleResult.Rejected => Left(Rejected())
  }
}

class RuleResultEitherOps(val ruleResultEither: Either[Rejected, Fulfilled]) extends AnyVal {

  def toRuleResult: RuleResult = ruleResultEither match {
    case Right(value) => value
    case Left(value) => value
  }
}

object RuleResultOps {
  implicit def from(ruleResult: RuleResult): RuleResultOps = new RuleResultOps(ruleResult)
  implicit def from(ruleResultEither: Either[Rejected, Fulfilled]): RuleResultEitherOps = new RuleResultEitherOps(ruleResultEither)
}