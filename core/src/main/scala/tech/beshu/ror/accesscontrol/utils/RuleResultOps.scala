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

import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}

import scala.language.implicitConversions

class RuleResultOps[B <: BlockContext](val ruleResult: RuleResult[B]) extends AnyVal {

  def toEither: Either[Rejected[B], Fulfilled[B]] = ruleResult match {
    case fulfilled: Fulfilled[B] => Right(fulfilled)
    case _: RuleResult.Rejected[B] => Left(Rejected())
  }
}

class RuleResultEitherOps[B <: BlockContext](val ruleResultEither: Either[Rejected[B], Fulfilled[B]]) extends AnyVal {

  def toRuleResult: RuleResult[B] = ruleResultEither match {
    case Right(value) => value
    case Left(value) => value
  }
}

object RuleResultOps {
  implicit def from[B <: BlockContext](ruleResult: RuleResult[B]): RuleResultOps[B] =
    new RuleResultOps(ruleResult)

  implicit def from[B <: BlockContext](ruleResultEither: Either[Rejected[B], Fulfilled[B]]): RuleResultEitherOps[B] =
    new RuleResultEitherOps(ruleResultEither)
}