package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.aDomain.{BasicAuth, Secret}

class AuthKeyRule(settings: BasicAuthenticationRule.Settings)
  extends BasicAuthenticationRule(settings)
    with Logging {

  override val name: Rule.Name = AuthKeyRule.name

  override protected def compare(configuredAuthKey: Secret,
                                 basicAuth: BasicAuth): Task[Boolean] = Task {
    configuredAuthKey === Secret(basicAuth.colonSeparatedString)
  }
}

object AuthKeyRule {
  val name = Rule.Name("auth_key")
}
