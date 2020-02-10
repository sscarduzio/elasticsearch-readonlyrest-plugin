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
package tech.beshu.ror.accesscontrol.blocks.rules

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaHideAppsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{MatchingAlwaysRule, RegularRule}
import tech.beshu.ror.accesscontrol.domain.KibanaApp
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._

class KibanaHideAppsRule(val settings: Settings)
  extends RegularRule with MatchingAlwaysRule with Logging {

  override val name: Rule.Name = KibanaHideAppsRule.name

  override def process(requestContext: RequestContext,
                       blockContext: BlockContext): Task[BlockContext] = Task {
    blockContext.loggedUser match {
      case Some(user) =>
        logger.debug(s"setting hidden apps for user ${user.show}: ${settings.kibanaAppsToHide.toList.map(_.show).mkString(",")}")
        blockContext.withHiddenKibanaApps(settings.kibanaAppsToHide.toSortedSet)
      case None =>
        blockContext
    }
  }
}

object KibanaHideAppsRule {
  val name = Rule.Name("kibana_hide_apps")

  final case class Settings(kibanaAppsToHide: NonEmptySet[KibanaApp])

}
