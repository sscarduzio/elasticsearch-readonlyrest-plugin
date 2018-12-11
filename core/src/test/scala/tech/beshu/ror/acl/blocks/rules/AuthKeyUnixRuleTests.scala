package tech.beshu.ror.acl.blocks.rules

class AuthKeyUnixRuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeyUnixRuleTests].getSimpleName
  override protected val rule: BasicAuthenticationRule = new AuthKeyUnixRule(BasicAuthenticationRule.Settings(
    "test:$6$rounds=65535$d07dnv4N$QeErsDT9Mz.ZoEPXW3dwQGL7tzwRz.eOrTBepIwfGEwdUAYSy/NirGoOaNyPx8lqiR6DYRSsDzVvVbhP4Y9wf0"
  ))
}
