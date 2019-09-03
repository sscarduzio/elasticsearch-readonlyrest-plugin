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
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.rules.BaseAuthorizationRule.AuthorizationResult
import tech.beshu.ror.accesscontrol.blocks.rules.BaseAuthorizationRule.AuthorizationResult.{Authorized, Unauthorized}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances.stringUserIdNT
import tech.beshu.ror.accesscontrol.blocks.rules.utils.{Matcher, MatcherWithWildcardsScalaAdapter, StringTNaturalTransformation}
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser, User}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.utils.MatcherWithWildcards
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._

class ExternalAuthorizationRule(val settings: ExternalAuthorizationRule.Settings)
  extends BaseAuthorizationRule {

  private val userMatcher: Matcher = new MatcherWithWildcardsScalaAdapter(
    new MatcherWithWildcards(settings.users.map(_.value.value).toSortedSet.asJava)
  )

  override val name: Rule.Name = ExternalAuthorizationRule.name

  override protected def authorize(requestContext: RequestContext,
                                   blockContext: BlockContext,
                                   user: LoggedUser): Task[AuthorizationResult] = {
    if(userMatcher.`match`(user.id)) checkUserGroups(user, requestContext.currentGroup.toOption, blockContext)
    else Task.now(Unauthorized)
  }

  private def checkUserGroups(user: LoggedUser,
                              currentGroup: Option[Group],
                              blockContext: BlockContext): Task[AuthorizationResult] = {
    settings
      .service
      .grantsFor(user)
      .map { userGroups =>
        UniqueNonEmptyList.fromSortedSet(settings.permittedGroups.intersect(userGroups)) match {
          case None =>
            Unauthorized
          case Some(determinedAvailableGroups) =>
            currentGroup match {
              case Some(group) if !determinedAvailableGroups.contains(group) =>
                Unauthorized
              case Some(_) | None =>
                Authorized(determinedAvailableGroups)
            }
        }
      }
  }

}

object ExternalAuthorizationRule {
  val name = Rule.Name("groups_provider_authorization")

  final case class Settings(service: ExternalAuthorizationService,
                            permittedGroups: UniqueNonEmptyList[Group],
                            users: NonEmptySet[User.Id])

}