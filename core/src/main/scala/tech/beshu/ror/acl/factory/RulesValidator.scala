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
package tech.beshu.ror.acl.factory

import cats.data.Validated._
import cats.data.{NonEmptyList, Validated, _}
import cats.syntax.all._
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.acl.blocks.rules.{ActionsRule, KibanaAccessRule, Rule}

object RulesValidator {

  def validate(rules: NonEmptyList[Rule]): ValidatedNel[ValidationError, Unit] = {
    (
      validateAuthorizationWithAuthenticationPrinciple(rules),
      validateKibanaAccessRuleAndActionsRuleSeparationPrinciple(rules)
    ).mapN { case _ => () }
  }

  private def validateAuthorizationWithAuthenticationPrinciple(rules: NonEmptyList[Rule]): ValidatedNel[ValidationError, Unit] = {
    rules.find(_.isInstanceOf[AuthorizationRule]) match {
      case None => Validated.Valid(())
      case Some(_) if rules.exists(_.isInstanceOf[AuthenticationRule]) => Validated.Valid(())
      case Some(_) => Validated.Invalid(NonEmptyList.one(ValidationError.AuthorizationWithoutAuthentication))
    }
  }

  private def validateKibanaAccessRuleAndActionsRuleSeparationPrinciple(rules: NonEmptyList[Rule]): ValidatedNel[ValidationError, Unit] = {
    val kibanaAccessRules = rules.collect { case r: KibanaAccessRule => r }
    val actionsRules = rules.collect { case r: ActionsRule => r}
    (kibanaAccessRules, actionsRules) match {
      case (Nil, Nil) => Validated.Valid(())
      case (Nil, _) => Validated.Valid(())
      case (_, Nil) => Validated.Valid(())
      case (_, _) => Validated.Invalid(NonEmptyList.one(ValidationError.KibanaAccessRuleTogetherWithActionsRule))
    }
  }

  sealed trait ValidationError
  object ValidationError {
    case object AuthorizationWithoutAuthentication extends ValidationError
    case object KibanaAccessRuleTogetherWithActionsRule extends ValidationError
  }

}
