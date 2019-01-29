package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern

import cats.implicits._
import monix.eval.Task
import org.apache.commons.codec.digest.Crypt.crypt
import tech.beshu.ror.acl.aDomain.{BasicAuth, Secret, User}

class AuthKeyUnixRule(settings: BasicAuthenticationRule.Settings)
  extends BasicAuthenticationRule(settings) {

  override val name: Rule.Name = AuthKeyUnixRule.name

  override protected def compare(configuredAuthKey: Secret,
                                 basicAuth: BasicAuth): Task[Boolean] = Task {
    configuredAuthKey.value.split(":").toList match {
      case user :: pass :: Nil if User.Id(user) === basicAuth.user =>
        configuredAuthKey === roundHash(pass, basicAuth)
      case _ =>
        false
    }
  }

  private def roundHash(key: String, basicAuth: BasicAuth): Secret = {
    val m = AuthKeyUnixRule.pattern.matcher(key)
    if (m.find) Secret(s"${basicAuth.user.value}:${crypt(basicAuth.secret.value, m.group(1))}")
    else Secret.empty
  }
}

object AuthKeyUnixRule {
  val name = Rule.Name("auth_key_unix")

  private val pattern = Pattern.compile("((?:[^$]*\\$){3}[^$]*).*")
}
