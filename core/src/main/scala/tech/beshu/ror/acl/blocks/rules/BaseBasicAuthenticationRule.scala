package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.aDomain.{BasicAuth, LoggedUser, Secret}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.BasicAuthenticationRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._

abstract class BaseBasicAuthenticationRule
  extends AuthenticationRule
    with Logging {

  protected def authenticate(basicAuth: BasicAuth): Task[Boolean]

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.unit
    .flatMap { _ =>
      requestContext.basicAuth match {
        case Some(credentials) =>
          logger.debug(s"Attempting Login as: ${credentials.user} rc: $requestContext")
          authenticate(credentials)
            .map {
              case true => Fulfilled(blockContext.withLoggedUser(LoggedUser(credentials.user)))
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

  protected def compare(configuredAuthKey: Secret, basicAuth: BasicAuth): Task[Boolean]
}

object BasicAuthenticationRule {

  final case class Settings(authKey: Secret)

}