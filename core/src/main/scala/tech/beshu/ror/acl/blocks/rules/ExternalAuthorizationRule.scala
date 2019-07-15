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

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.domain.{Group, LoggedUser, User}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.acl.blocks.rules.utils.{Matcher, MatcherWithWildcardsScalaAdapter, StringTNaturalTransformation}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.utils.MatcherWithWildcards
import scala.collection.JavaConverters._
import StringTNaturalTransformation.instances.stringUserIdNT
import tech.beshu.ror.acl.blocks.rules.BaseAuthorizationRule.AuthorizationResult
import tech.beshu.ror.acl.blocks.rules.BaseAuthorizationRule.AuthorizationResult.{Authorized, Unauthorized}

class ExternalAuthorizationRule(val settings: ExternalAuthorizationRule.Settings)
  extends BaseAuthorizationRule {

  private val userMatcher: Matcher = new MatcherWithWildcardsScalaAdapter(
    new MatcherWithWildcards(settings.users.map(_.value.value).toSortedSet.asJava)
  )

  override val name: Rule.Name = ExternalAuthorizationRule.name

  override protected def authorize(requestContext: RequestContext,
                                   blockContext: BlockContext,
                                   user: LoggedUser): Task[AuthorizationResult] = {
    if(userMatcher.`match`(user.id)) checkUserGroups(user, blockContext)
    else Task.now(Unauthorized)
  }

  private def checkUserGroups(user: LoggedUser, blockContext: BlockContext): Task[AuthorizationResult] = {
    settings
      .service
      .grantsFor(user)
      .map { userGroups =>
        NonEmptySet.fromSet(settings.permittedGroups.toSortedSet.intersect(userGroups)) match {
          case None =>
            Unauthorized
          case Some(determinedAvailableGroups) =>
            blockContext.currentGroup match {
              case Some(currentGroup) if !determinedAvailableGroups.contains(currentGroup) =>
                Unauthorized
              case Some(currentGroup) =>
                Authorized(currentGroup, determinedAvailableGroups)
              case None =>
                Authorized(pickCurrentGroupFrom(determinedAvailableGroups), determinedAvailableGroups)
            }
        }
      }
  }

}

object ExternalAuthorizationRule {
  val name = Rule.Name("groups_provider_authorization")

  final case class Settings(service: ExternalAuthorizationService,
                            permittedGroups: NonEmptySet[Group],
                            users: NonEmptySet[User.Id])

}