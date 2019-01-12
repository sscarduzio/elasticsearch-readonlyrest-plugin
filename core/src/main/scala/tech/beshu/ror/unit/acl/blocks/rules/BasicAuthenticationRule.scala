package tech.beshu.ror.unit.acl.blocks.rules

import org.apache.logging.log4j.scala.Logging
import monix.eval.Task
import tech.beshu.ror.unit.acl.blocks.BlockContext
import tech.beshu.ror.unit.acl.blocks.rules.BasicAuthenticationRule.Settings
import tech.beshu.ror.unit.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.unit.acl.blocks.rules.Rule.{AuthenticationRule, RuleResult}
import tech.beshu.ror.unit.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.AuthData
import tech.beshu.ror.commons.domain.LoggedUser
import tech.beshu.ror.commons.domain.User.Id
import tech.beshu.ror.commons.header.ToTuple._
import tech.beshu.ror.utils.BasicAuthUtils
import tech.beshu.ror.utils.BasicAuthUtils.BasicAuth

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._

abstract class BasicAuthenticationRule(val settings: Settings)
  extends AuthenticationRule
    with Logging {

  protected def authenticate(configuredAuthKey: AuthData, basicAuth: BasicAuth): Boolean

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    BasicAuthUtils
      .getBasicAuthFromHeaders(requestContext.headers.map(_.toTuple).toMap.asJava).asScala
      .map { credentials =>
        logger.debug(s"Attempting Login as: ${credentials.getUserName} rc: $requestContext")
        if (authenticate(settings.authKey, credentials))
          Fulfilled(blockContext.setLoggedUser(LoggedUser(Id(credentials.getUserName))))
        else
          Rejected
      }
      .getOrElse {
        logger.debug("No basic auth")
        Rejected
      }
  }
}

object BasicAuthenticationRule {

  final case class Settings(authKey: AuthData)

}