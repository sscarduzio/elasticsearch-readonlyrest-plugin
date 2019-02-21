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

import cats.data.NonEmptyList
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule}

object RulesValidator {

  def validate(rules: NonEmptyList[Rule]): Either[ValidationError, Unit] = {
    for {
      _ <- validateAuthorizationWithAuthenticationPrinciple(rules)
    } yield ()
  }

  private def validateAuthorizationWithAuthenticationPrinciple(rules: NonEmptyList[Rule]) = {
    rules.find(_.isInstanceOf[AuthorizationRule]) match {
      case None => Right(())
      case Some(_) if rules.exists(_.isInstanceOf[AuthenticationRule]) => Right(())
      case Some(_) => Left(ValidationError.AuthorizationWithoutAuthentication)
    }
  }

  sealed trait ValidationError
  object ValidationError {
    case object AuthorizationWithoutAuthentication extends ValidationError
  }

}
