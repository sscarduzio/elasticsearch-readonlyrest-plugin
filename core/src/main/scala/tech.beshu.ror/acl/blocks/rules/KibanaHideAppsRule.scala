package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.KibanaHideAppsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.MatchingAlwaysRule
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.Header.Name.kibanaHiddenApps
import tech.beshu.ror.commons.aDomain.{Header, KibanaApp}
import tech.beshu.ror.commons.orders._
import tech.beshu.ror.commons.show.logs._
import cats.implicits._

class KibanaHideAppsRule(settings: Settings)
  extends MatchingAlwaysRule with StrictLogging {

  override val name: Rule.Name = Rule.Name("hosts_local")

  private val kibanaAppsHeader = new Header(
    kibanaHiddenApps,
    settings.kibanaAppsToHide.toSortedSet.map(_.value).mkString(",")
  )

  override def process(context: RequestContext): Task[Unit] = Task.now {
    context
      .loggedUser
      .foreach { user =>
        logger.debug(s"setting hidden apps for user ${user.show}: ${kibanaAppsHeader.value}")
        context.setResponseHeader(kibanaAppsHeader)
      }
  }
}

object KibanaHideAppsRule {

  final case class Settings(kibanaAppsToHide: NonEmptySet[KibanaApp])

}
