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
package tech.beshu.ror.accesscontrol.utils

import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.domain.Operation

import scala.language.implicitConversions

class RuleResultOps[T <: Operation](val ruleResult: RuleResult[T]) extends AnyVal {

  def toEither: Either[Rejected, Fulfilled[T]] = ruleResult match {
    case fulfilled: Fulfilled[T] => Right(fulfilled)
    case _: RuleResult.Rejected => Left(Rejected())
  }
}

class RuleResultEitherOps[T <: Operation](val ruleResultEither: Either[Rejected, Fulfilled[T]]) extends AnyVal {

  def toRuleResult: RuleResult[T] = ruleResultEither match {
    case Right(value) => value
    case Left(value) => value
  }
}

object RuleResultOps {
  implicit def from[T <: Operation](ruleResult: RuleResult[T]): RuleResultOps[T] =
    new RuleResultOps(ruleResult)
  implicit def from[T <: Operation](ruleResultEither: Either[Rejected, Fulfilled[T]]): RuleResultEitherOps[T] =
    new RuleResultEitherOps(ruleResultEither)
}