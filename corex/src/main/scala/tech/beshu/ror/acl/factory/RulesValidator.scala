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
