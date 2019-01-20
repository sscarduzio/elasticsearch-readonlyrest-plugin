package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.aDomain.Group
import tech.beshu.ror.acl.blocks.definitions.UserDef
import tech.beshu.ror.acl.blocks.rules.GroupsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, RuleResult}
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContext.Id._

import scala.collection.SortedSet

class GroupsRule(val settings: Settings)
  extends AuthenticationRule
    with AuthorizationRule
    with Logging {

  override val name: Rule.Name = GroupsRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task
    .unit
    .flatMap { _ =>
      NonEmptySet.fromSet(resolveGroups(requestContext, blockContext)) match {
        case None => Task.now(Rejected)
        case Some(groups) =>
          blockContext.currentGroup match {
            case Some(preferredGroup) if !groups.contains(preferredGroup) => Task.now(Rejected)
            case _ => continueCheckingWithUserDefinitions(requestContext, blockContext, groups)
          }
      }
    }


  private def continueCheckingWithUserDefinitions(requestContext: RequestContext,
                                                  blockContext: BlockContext,
                                                  resolvedGroups: NonEmptySet[Group]): Task[RuleResult] = {
    blockContext.loggedUser match {
      case Some(user) =>
        NonEmptySet.fromSet(settings.usersDefinitions.filter(_.username === user.id)) match {
          case None =>
            Task.now(Rejected)
          case Some(filteredUserDefinitions) =>
            tryToAuthorizeAndAuthenticateUsing(filteredUserDefinitions, requestContext, blockContext, resolvedGroups)
        }
      case None =>
        tryToAuthorizeAndAuthenticateUsing(settings.usersDefinitions, requestContext, blockContext, resolvedGroups)
    }
  }

  private def tryToAuthorizeAndAuthenticateUsing(userDefs: NonEmptySet[UserDef],
                                                 requestContext: RequestContext,
                                                 blockContext: BlockContext,
                                                 resolvedGroups: NonEmptySet[Group]) = {
    userDefs
      .reduceLeftTo(authorizeAndAuthenticate(requestContext, blockContext, resolvedGroups)) {
        case (lastUserDefResult, nextUserDef) =>
          lastUserDefResult.flatMap {
            case success@Some(_) => Task.now(success)
            case None => authorizeAndAuthenticate(requestContext, blockContext, resolvedGroups)(nextUserDef)
          }
      }
      .map {
        case Some(newBlockContext) => Fulfilled(newBlockContext)
        case None => Rejected
      }
  }

  private def authorizeAndAuthenticate(requestContext: RequestContext,
                                       blockContext: BlockContext,
                                       resolvedGroups: NonEmptySet[Group])
                                      (userDef: UserDef) = {
    if (userDef.groups.intersect(resolvedGroups).isEmpty) Task.now(None)
    else {
      userDef
        .authenticationRule
        .check(requestContext, blockContext)
        .map {
          case RuleResult.Rejected => None
          case RuleResult.Fulfilled(newBlockContext) => Some {
            newBlockContext
              .withAddedAvailableGroups(userDef.groups)
              .withCurrentGroup(pickCurrentGroupFrom(resolvedGroups))
          }
        }
        .onErrorRecover { case ex =>
          logger.error(s"Authentication error; req=${requestContext.id.show}", ex)
          None
        }
    }
  }

  private def resolveGroups(requestContext: RequestContext,
                            blockContext: BlockContext) = {
    SortedSet.empty[Group] ++ settings
      .groups
      .map(_.getValue(requestContext.variablesResolver, blockContext).toOption)
      .toSortedSet
      .flatten
  }

  private def pickCurrentGroupFrom(resolvedGroups: NonEmptySet[Group]): Group = {
    resolvedGroups.toSortedSet.toList.minBy(_.value)
  }
}

object GroupsRule {
  val name = Rule.Name("groups")

  final case class Settings(groups: NonEmptySet[Value[Group]],
                            usersDefinitions: NonEmptySet[UserDef])

}
