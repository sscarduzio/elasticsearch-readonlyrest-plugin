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
package tech.beshu.ror.acl.blocks

import cats.implicits._
import cats.Order
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.blocks.rules._
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.show.logs._

class RuleOrdering extends Ordering[Rule] with Logging {

  override def compare(rule1: Rule, rule2: Rule): Int = {
    val rule1TypeIndex = RuleOrdering.orderedListOrRuleType.indexOf(rule1.getClass)
    val rule2TypeIndex = RuleOrdering.orderedListOrRuleType.indexOf(rule2.getClass)
    (rule1TypeIndex, rule2TypeIndex) match {
      case (-1, -1) =>
        logger.warn(s"No order defined for rules: ${rule1.name.show}, ${rule1.name.show}")
        implicitly[Order[Rule.Name]].compare(rule1.name, rule2.name)
      case (-1, _) =>
        logger.warn(s"No order defined for rule: ${rule1.name.show}")
        1
      case (_, -1) =>
        logger.warn(s"No order defined for rule: ${rule2.name.show}")
        -1
      case (i1, i2) =>
        i1 compareTo i2
    }
  }
}

object RuleOrdering {
  private val orderedListOrRuleType: Seq[Class[_ <: Rule]] = Seq(
    // Authentication rules must come first because they set the user information which further rules might rely on.
    classOf[AuthKeyRule],
    classOf[AuthKeySha1Rule],
    classOf[AuthKeySha256Rule],
    classOf[AuthKeySha512Rule],
    classOf[AuthKeyUnixRule],
    classOf[ProxyAuthRule],
    classOf[JwtAuthRule],
    classOf[RorKbnAuthRule],
    // then we could check potentially slow async rules
    classOf[ExternalAuthenticationRule],
    classOf[GroupsRule],
    // Inspection rules next; these act based on properties of the request.
    classOf[KibanaAccessRule],
    classOf[LocalHostsRule],
    classOf[SnapshotsRule],
    classOf[RepositoriesRule],
    classOf[XForwardedForRule],
    classOf[ApiKeysRule],
    classOf[SessionMaxIdleRule],
    classOf[UriRegexRule],
    classOf[MaxBodyLengthRule],
    classOf[MethodsRule],
    classOf[HeadersAndRule],
    classOf[HeadersOrRule],
    classOf[IndicesRule],
    classOf[ActionsRule],
    classOf[UsersRule],
    // all authorization rules should be placed before any authentication rule
    classOf[ExternalAuthorizationRule],
    // At the end the sync rule chain are those that can mutate the client request.
    classOf[KibanaHideAppsRule],
    classOf[KibanaIndexRule],
    // Stuff to do later, at search time
    classOf[FieldsRule],
    classOf[FilterRule]
  )
}
