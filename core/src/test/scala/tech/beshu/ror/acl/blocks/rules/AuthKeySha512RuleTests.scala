package tech.beshu.ror.acl.blocks.rules

import tech.beshu.ror.commons.aDomain.AuthData

class AuthKeySha512RuleTests extends BasicAuthenticationTestTemplate {

  override protected def ruleName: String = classOf[AuthKeySha512Rule].getSimpleName
  override protected val rule = new AuthKeySha512Rule(BasicAuthenticationRule.Settings(
    AuthData("3586d5752240fd09e967383d3f1bad025bbc6953ba7c6d2135670631b4e326fee0cc8bd81addb9f6de111b9c380505b5ea0531598c21b0906d8e726f24e0dbe2")
  ))
}
