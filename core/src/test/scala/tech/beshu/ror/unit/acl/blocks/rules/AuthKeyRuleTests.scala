package tech.beshu.ror.unit.acl.blocks.rules

import tech.beshu.ror.commons.aDomain.AuthData

class AuthKeyRuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeyRule].getSimpleName
  override protected val rule = new AuthKeyRule(BasicAuthenticationRule.Settings(AuthData("logstash:logstash")))
}
