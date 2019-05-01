/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
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
import tech.beshu.ror.acl.domain.Header.Name.kibanaHiddenApps
import tech.beshu.ror.acl.domain.{Header, KibanaApp}
import tech.beshu.ror.acl.show.logs._

class KibanaHideAppsRule(val settings: Settings)
  extends MatchingAlwaysRule with Logging {

  override val name: Rule.Name = KibanaHideAppsRule.name

  private val kibanaAppsHeader = new Header(
    kibanaHiddenApps,
    NonEmptyString.unsafeFrom(settings.kibanaAppsToHide.toSortedSet.map(_.value.value).mkString(","))
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
