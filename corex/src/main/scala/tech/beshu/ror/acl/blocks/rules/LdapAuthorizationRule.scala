package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import cats.data.{NonEmptySet, OptionT}
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.blocks.definitions.LdapAuthService
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
    fetchLdapGroups(user)
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

  private def fetchLdapGroups(user: LoggedUser) = {
    val result = for {
      ldapUser <- OptionT(settings.ldap.ldapUser(user.id))
      groups <- OptionT.liftF(settings.ldap.groupsOf(ldapUser))
    } yield groups
    result.value.map(_.getOrElse(Set.empty))
  }
}

object LdapAuthorizationRule {
  val name = Rule.Name("ldap_authentication")

  final case class Settings(ldap: LdapAuthService,
                            permittedGroups: NonEmptySet[Group]) // todo: shouldn't group be variable?
}