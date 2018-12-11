package tech.beshu.ror.acl.blocks.rules


class AuthKeySha1RuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeySha1Rule].getSimpleName
  override protected val rule = new AuthKeySha1Rule(BasicAuthenticationRule.Settings("4338fa3ea95532196849ae27615e14dda95c77b1"))
}
