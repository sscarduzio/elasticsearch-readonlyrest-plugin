package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import cats.data.{NonEmptySet, OptionT}
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapAuthorizationService
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.blocks.rules.BaseAuthorizationRule.AuthorizationResult
import tech.beshu.ror.acl.blocks.rules.BaseAuthorizationRule.AuthorizationResult.{Authorized, Unauthorized}
import tech.beshu.ror.acl.blocks.rules.LdapAuthorizationRule.Settings
import tech.beshu.ror.acl.domain.{Group, LoggedUser}
import tech.beshu.ror.acl.request.RequestContext

import scala.collection.SortedSet

class LdapAuthorizationRule(val settings: Settings)
  extends BaseAuthorizationRule {

  override val name: Rule.Name = LdapAuthorizationRule.name

  override protected def authorize(requestContext: RequestContext,
                                   blockContext: BlockContext,
                                   user: LoggedUser): Task[AuthorizationResult] = {
    blockContext.currentGroup match {
      case Some(currentGroup) if !settings.permittedGroups.contains(currentGroup) =>
        Task.now(Unauthorized)
      case Some(_) | None =>
        authorizeWithLdapGroups(blockContext, user)
    }
  }

  private def authorizeWithLdapGroups(blockContext: BlockContext, user: LoggedUser): Task[AuthorizationResult] = {
    settings
      .ldap
      .groupsOf(user.id)
      .map(_.intersect(settings.permittedGroups.toSortedSet))
      .map(groups => NonEmptySet.fromSet(SortedSet.empty[Group] ++ groups))
      .map {
        case None =>
          Unauthorized
        case Some(availableGroups) =>
          blockContext.currentGroup match {
            case Some(currentGroup) if !availableGroups.contains(currentGroup) =>
              Unauthorized
            case Some(currentGroup) =>
              Authorized(currentGroup, availableGroups)
            case None =>
              Authorized(pickCurrentGroupFrom(availableGroups), availableGroups)
          }
      }
  }

}

object LdapAuthorizationRule {
  val name = Rule.Name("ldap_authentication")

  final case class Settings(ldap: LdapAuthorizationService,
                            permittedGroups: NonEmptySet[Group]) // todo: shouldn't group be variable?
}