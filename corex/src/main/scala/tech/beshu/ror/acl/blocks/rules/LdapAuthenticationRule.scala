package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.LdapAuthenticationService
import tech.beshu.ror.acl.blocks.definitions.LdapAuthenticationService.Credentials
import tech.beshu.ror.acl.blocks.rules.LdapAuthenticationRule.Settings
import tech.beshu.ror.acl.domain.BasicAuth

class LdapAuthenticationRule(val settings: Settings)
  extends BaseBasicAuthenticationRule {

  override val name: Rule.Name = LdapAuthenticationRule.name

  override protected def authenticate(basicAuthCredentials: BasicAuth): Task[Boolean] =
    settings.ldap.authenticate(Credentials(basicAuthCredentials.user, basicAuthCredentials.secret))
}


object LdapAuthenticationRule {
  val name = Rule.Name("ldap_authentication")

  final case class Settings(ldap: LdapAuthenticationService)
}
