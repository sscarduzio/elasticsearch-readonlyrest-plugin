package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.utils.BasicAuthOps._
import tech.beshu.ror.acl.utils.ScalaExt._
import tech.beshu.ror.acl.aDomain.AuthData
import tech.beshu.ror.utils.BasicAuthUtils

class AuthKeyRule(settings: BasicAuthenticationRule.Settings)
  extends BasicAuthenticationRule(settings)
    with Logging {

  override val name: Rule.Name = AuthKeyRule.name

  override protected def authenticate(configuredAuthKey: AuthData,
                                      basicAuth: BasicAuthUtils.BasicAuth): Boolean = {
    basicAuth
      .tryDecode
      .map(_ === configuredAuthKey)
      .getOr { ex =>
        logger.warn("Exception while authentication", ex)
        false
      }
  }
}

object AuthKeyRule {
  val name = Rule.Name("auth_key")
}
