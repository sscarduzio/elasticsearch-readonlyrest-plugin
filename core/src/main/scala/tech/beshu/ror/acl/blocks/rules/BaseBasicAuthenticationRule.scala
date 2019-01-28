package tech.beshu.ror.acl.blocks.rules

import org.apache.logging.log4j.scala.Logging
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.User.Id
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.BasicAuthenticationRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.aDomain.{AuthData, LoggedUser}
import tech.beshu.ror.acl.header.ToTuple._
import tech.beshu.ror.utils.BasicAuthUtils.{BasicAuth, getBasicAuthFromHeaders}

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._

abstract class BaseBasicAuthenticationRule
  extends AuthenticationRule
    with Logging {

  protected def authenticate(basicAuth: BasicAuth): Task[Boolean]

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.unit
    .flatMap { _ =>
      getBasicAuthFromHeaders(requestContext.headers.map(_.toTuple).toMap.asJava).asScala match {
        case Some(credentials) =>
          logger.debug(s"Attempting Login as: ${credentials.getUserName} rc: $requestContext")
          authenticate(credentials)
            .map {
              case true => Fulfilled(blockContext.withLoggedUser(LoggedUser(Id(credentials.getUserName))))
              case false => Rejected
            }
        case None =>
          logger.debug("No basic auth")
          Task.now(Rejected)
      }
    }
}

abstract class BasicAuthenticationRule(val settings: Settings)
  extends BaseBasicAuthenticationRule {

  override protected def authenticate(basicAuth: BasicAuth): Task[Boolean] =
    compare(settings.authKey, basicAuth)

  protected def compare(configuredAuthKey: AuthData, basicAuth: BasicAuth): Task[Boolean]
}

object BasicAuthenticationRule {

  final case class Settings(authKey: AuthData)

}