package tech.beshu.ror.acl.utils

import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}

import scala.language.implicitConversions

class RuleResultOps(val ruleResult: RuleResult) extends AnyVal {

  def toEither: Either[Rejected.type, Fulfilled] = ruleResult match {
    case fulfilled: Fulfilled => Right(fulfilled)
    case rejected: RuleResult.Rejected.type => Left(rejected)
  }
}

class RuleResultEitherOps(val ruleResultEither: Either[Rejected.type, Fulfilled]) extends AnyVal {

  def toRuleResult: RuleResult = ruleResultEither match {
    case Right(value) => value
    case Left(value) => value
  }
}

object RuleResultOps {
  implicit def from(ruleResult: RuleResult): RuleResultOps = new RuleResultOps(ruleResult)
  implicit def from(ruleResultEither: Either[Rejected.type, Fulfilled]): RuleResultEitherOps = new RuleResultEitherOps(ruleResultEither)
}