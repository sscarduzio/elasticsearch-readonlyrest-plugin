package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.aDomain.Group
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.blocks.definitions.UserDef
import tech.beshu.ror.acl.blocks.rules.GroupsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.acl.request.{RequestContext, RequestGroup}
import tech.beshu.ror.acl.request.RequestContext.Id._

import scala.collection.SortedSet

class GroupsRule(val settings: Settings)
  extends RegularRule with Logging {

  override val name: Rule.Name = GroupsRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task
    .unit
    .flatMap { _ =>
      NonEmptySet.fromSet(resolveGroups(requestContext, blockContext)) match {
        case None => Task.now(Rejected)
        case Some(groups) =>
          preferredGroupFrom(requestContext) match {
            case Some(preferredGroup) if !groups.contains(preferredGroup) => Task.now(Rejected)
            case _ => continueCheckingWithUserDefinitions(requestContext, blockContext, groups)
          }
      }
    }


  private def continueCheckingWithUserDefinitions(requestContext: RequestContext,
                                                  blockContext: BlockContext,
                                                  groups: NonEmptySet[Group]): Task[RuleResult] = {
    settings
      .usersDefinitions
      .reduceLeftTo(authorizeAndAuthenticate(requestContext, blockContext, groups)) {
        case (lastUserDefResult, nextUserDef) =>
          lastUserDefResult.flatMap {
            case success@Some(_) => Task.now(success)
            case None => authorizeAndAuthenticate(requestContext, blockContext, groups)(nextUserDef)
          }
      }
      .map {
        case Some(newBlockContext) => Fulfilled(newBlockContext)
        case None => Rejected
      }
  }

  // todo: what about "populating the headers"?
  private def authorizeAndAuthenticate(requestContext: RequestContext,
                                       blockContext: BlockContext,
                                       groups: NonEmptySet[Group])
                                      (userDef: UserDef) = {
    // todo: check user name?
    if (userDef.groups.intersect(groups).isEmpty) Task.now(None)
    else {
      userDef
        .authenticationRule
        .check(requestContext, blockContext)
        .map {
          case RuleResult.Rejected => None
          case RuleResult.Fulfilled(newBlockContext) => Some(newBlockContext)
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

  private def preferredGroupFrom(requestContext: RequestContext) = {
    requestContext.currentGroup match {
      case RequestGroup.AGroup(userGroup) => Some(userGroup)
      case RequestGroup.Empty | RequestGroup.`N/A` => None
    }
  }
}

object GroupsRule {
  val name = Rule.Name("groups")

  final case class Settings(groups: NonEmptySet[Value[Group]],
                            usersDefinitions: NonEmptySet[UserDef])

}
