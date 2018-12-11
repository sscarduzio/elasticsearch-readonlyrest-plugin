package tech.beshu.ror.acl.blocks.rules

class AuthKeyRuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeyRule].getSimpleName
  override protected val rule = new AuthKeyRule(BasicAuthenticationRule.Settings("logstash:logstash"))
}
