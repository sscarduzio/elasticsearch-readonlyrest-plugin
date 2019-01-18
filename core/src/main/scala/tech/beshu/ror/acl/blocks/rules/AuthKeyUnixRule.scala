package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern

import cats.implicits._
import org.apache.commons.codec.digest.Crypt.crypt
import tech.beshu.ror.acl.aDomain.AuthData
import tech.beshu.ror.utils.BasicAuthUtils

class AuthKeyUnixRule(settings: BasicAuthenticationRule.Settings)
  extends BasicAuthenticationRule(settings) {

  override val name: Rule.Name = AuthKeyUnixRule.name

  override protected def authenticate(configuredAuthKey: AuthData,
                                      basicAuth: BasicAuthUtils.BasicAuth): Boolean = {
    configuredAuthKey.value.split(":").toList match {
      case user :: pass :: Nil if user == basicAuth.getUserName =>
        configuredAuthKey === roundHash(pass, basicAuth)
      case _ =>
        false
    }
  }

  private def roundHash(key: String, basicAuth: BasicAuthUtils.BasicAuth): AuthData = {
    val m = AuthKeyUnixRule.pattern.matcher(key)
    if (m.find) AuthData(s"${basicAuth.getUserName}:${crypt(basicAuth.getPassword, m.group(1))}")
    else AuthData.empty
  }
}

object AuthKeyUnixRule {
  val name = Rule.Name("auth_key_unix")

  private val pattern = Pattern.compile("((?:[^$]*\\$){3}[^$]*).*")
}
