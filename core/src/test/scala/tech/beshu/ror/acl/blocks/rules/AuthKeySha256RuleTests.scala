package tech.beshu.ror.acl.blocks.rules

import tech.beshu.ror.commons.aDomain.AuthData

class AuthKeySha256RuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeySha256Rule].getSimpleName
  override protected val rule = new AuthKeySha256Rule(BasicAuthenticationRule.Settings(AuthData("280ac6f756a64a80143447c980289e7e4c6918b92588c8095c7c3f049a13fbf9")))
}
