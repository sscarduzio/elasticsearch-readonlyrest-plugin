package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.aDomain.BasicAuth
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthenticationService

class ExternalAuthenticationRule(val settings: ExternalAuthenticationRule.Settings)
  extends BaseBasicAuthenticationRule {

  override val name: Rule.Name = ExternalAuthenticationRule.name

  override protected def authenticate(credentials: BasicAuth): Task[Boolean] =
    settings.service.authenticate(credentials.user, credentials.secret)
}

object ExternalAuthenticationRule {
  val name = Rule.Name("external_authentication")

  final case class Settings(service: ExternalAuthenticationService)
}