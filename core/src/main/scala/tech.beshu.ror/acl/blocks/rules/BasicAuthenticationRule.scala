package tech.beshu.ror.acl.blocks.rules

import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.BasicAuthenticationRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.domain.LoggedUser
import tech.beshu.ror.commons.domain.User.Id
import tech.beshu.ror.commons.header.ToTuple._
import tech.beshu.ror.utils.BasicAuthUtils
import tech.beshu.ror.utils.BasicAuthUtils.BasicAuth

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._

abstract class BasicAuthenticationRule(settings: Settings)
  extends AuthenticationRule
    with StrictLogging {

  protected def authenticate(configuredAuthKey: String, basicAuth: BasicAuth): Boolean

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

  final case class Settings(authKey: String)

}