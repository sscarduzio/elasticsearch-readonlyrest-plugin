package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.{Group, LoggedUser, User}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthorizationRule, RuleResult}
import tech.beshu.ror.acl.blocks.rules.utils.{Matcher, MatcherWithWildcardsScalaAdapter, StringTNaturalTransformation}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.utils.MatcherWithWildcards
import tech.beshu.ror.acl.utils.ScalaOps._

import scala.collection.JavaConverters._

class ExternalAuthorizationRule(val settings: ExternalAuthorizationRule.Settings)
  extends AuthorizationRule {

  import ExternalAuthorizationRule.stringUserIdNT

  private val userMatcher: Matcher = new MatcherWithWildcardsScalaAdapter(
    new MatcherWithWildcards(settings.users.map(_.value).toSortedSet.asJava)
  )

  override val name: Rule.Name = ExternalAuthorizationRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = {
    blockContext.loggedUser match {
      case Some(user) if userMatcher.`match`(user.id) => checkUserGroups(user, blockContext)
      case Some(_) | None => Task.now(Rejected)
    }
  }

  private def checkUserGroups(user: LoggedUser, blockContext: BlockContext): Task[RuleResult] = {
    settings
      .service
      .grantsFor(user)
      .map { userGroups =>
        NonEmptySet.fromSet(settings.permittedGroups.toSortedSet.intersect(userGroups)) match {
          case None => Rejected
          case Some(determinedAvailableGroups) =>
            blockContext.currentGroup match {
              case Some(currentGroup) if !determinedAvailableGroups.contains(currentGroup) =>
                Rejected
              case Some(_) =>
                Fulfilled {
                  blockContext
                    .withAddedAvailableGroups(determinedAvailableGroups)
                }
              case None =>
                Fulfilled {
                  blockContext
                    .withCurrentGroup(pickCurrentGroupFrom(determinedAvailableGroups))
                    .withAddedAvailableGroups(determinedAvailableGroups)
                }
            }
        }
      }
  }

  private def pickCurrentGroupFrom(resolvedGroups: NonEmptySet[Group]): Group = {
    resolvedGroups.toSortedSet.toList.minBy(_.value)
  }
}

object ExternalAuthorizationRule {
  val name = Rule.Name("groups_provider_authorization")

  final case class Settings(service: ExternalAuthorizationService,
                            permittedGroups: NonEmptySet[Group], // todo: shouldn't group be variable?
                            users: NonEmptySet[User.Id])


  private implicit val stringUserIdNT: StringTNaturalTransformation[User.Id] =
    StringTNaturalTransformation[User.Id](User.Id.apply, _.value)
}