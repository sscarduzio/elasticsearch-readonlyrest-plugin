package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.KibanaHideAppsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.MatchingAlwaysRule
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.Header.Name.kibanaHiddenApps
import tech.beshu.ror.commons.aDomain.{Header, KibanaApp}
import tech.beshu.ror.commons.show.logs._

class KibanaHideAppsRule(settings: Settings)
  extends MatchingAlwaysRule with StrictLogging {

  override val name: Rule.Name = Rule.Name("hosts_local")

  private val kibanaAppsHeader = new Header(
    kibanaHiddenApps,
    settings.kibanaAppsToHide.toSortedSet.map(_.value).mkString(",")
  )

  override def process(requestContext: RequestContext,
                       blockContext: BlockContext): Task[BlockContext] = Task.now {
    blockContext.loggedUser match {
      case Some(user) =>
        logger.debug(s"setting hidden apps for user ${user.show}: ${kibanaAppsHeader.value.show}")
        blockContext.setResponseHeader(kibanaAppsHeader)
      case None =>
        blockContext
    }
  }
}

object KibanaHideAppsRule {

  final case class Settings(kibanaAppsToHide: NonEmptySet[KibanaApp])

}
