package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.KibanaHideAppsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.MatchingAlwaysRule
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.aDomain.Header.Name.kibanaHiddenApps
import tech.beshu.ror.acl.aDomain.{Header, KibanaApp}
import tech.beshu.ror.acl.show.logs._

class KibanaHideAppsRule(val settings: Settings)
  extends MatchingAlwaysRule with Logging {

  override val name: Rule.Name = KibanaHideAppsRule.name

  private val kibanaAppsHeader = new Header(
    kibanaHiddenApps,
    NonEmptyString.unsafeFrom(settings.kibanaAppsToHide.toSortedSet.map(_.value).mkString(","))
  )

  override def process(requestContext: RequestContext,
                       blockContext: BlockContext): Task[BlockContext] = Task {
    blockContext.loggedUser match {
      case Some(user) =>
        logger.debug(s"setting hidden apps for user ${user.show}: ${kibanaAppsHeader.value.show}")
        blockContext.withAddedResponseHeader(kibanaAppsHeader)
      case None =>
        blockContext
    }
  }
}

object KibanaHideAppsRule {
  val name = Rule.Name("kibana_hide_apps")

  final case class Settings(kibanaAppsToHide: NonEmptySet[KibanaApp])

}
