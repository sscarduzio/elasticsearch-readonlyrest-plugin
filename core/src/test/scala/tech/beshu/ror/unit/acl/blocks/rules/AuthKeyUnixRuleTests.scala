package tech.beshu.ror.unit.acl.blocks.rules

import tech.beshu.ror.commons.aDomain.AuthData

class AuthKeyUnixRuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeyUnixRuleTests].getSimpleName
  override protected val rule: BasicAuthenticationRule = new AuthKeyUnixRule(BasicAuthenticationRule.Settings(
    AuthData("logstash:$6$rounds=65535$d07dnv4N$jh8an.nDSXG6PZlfVh5ehigYL8.5gtV.9yoXAOYFHTQvwPWhBdEIOxnS8tpbuIAk86shjJiqxeap5o0A1PoFI/")
  ))
}
