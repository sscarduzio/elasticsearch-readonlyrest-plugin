package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern

import org.apache.commons.codec.digest.Crypt.crypt
import tech.beshu.ror.utils.BasicAuthUtils

class AuthKeyUnixRule(settings: BasicAuthenticationRule.Settings)
  extends BasicAuthenticationRule(settings) {

  override val name: Rule.Name = AuthKeyUnixRule.name

  override protected def authenticate(configuredAuthKey: String,
                                      basicAuth: BasicAuthUtils.BasicAuth): Boolean = {
    configuredAuthKey.split(":").toList match {
      case user :: pass :: Nil if user == basicAuth.getUserName =>
        configuredAuthKey == roundHash(pass, basicAuth)
      case _ =>
        false
    }
  }

  private def roundHash(key: String, basicAuth: BasicAuthUtils.BasicAuth) = {
    val m = AuthKeyUnixRule.pattern.matcher(key)
    if (m.find) s"${basicAuth.getUserName}:${crypt(basicAuth.getPassword, m.group(1))}"
    else ""
  }
}

object AuthKeyUnixRule {
  val name = Rule.Name("auth_key_unix")

  private val pattern = Pattern.compile("((?:[^$]*\\$){3}[^$]*).*")
}
