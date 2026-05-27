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
package tech.beshu.ror.accesscontrol.blocks

import cats.data.{NonEmptyList, Validated, WriterT}
import cats.{Eq, Show}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.History.{BlockHistory, RuleHistory}
import tech.beshu.ror.accesscontrol.audit.LoggingContext
import tech.beshu.ror.accesscontrol.blocks.Block.*
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning.ImpersonationWarningSupport
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage
import tech.beshu.ror.accesscontrol.factory.BlockValidator
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.BlocksLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.request.{RequestContext, UserMetadataRequestContext}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import scala.language.implicitConversions

class Block(val name: Name,
            val policy: Policy,
            val verbosity: Verbosity,
            val audit: Audit,
            val rules: NonEmptyList[Rule])
           (implicit val loggingContext: LoggingContext)
  extends RequestIdAwareLogging {

  import Lifter.*

  def evaluate[B <: BlockContext : BlockContextUpdater](requestContext: RequestContext.Aux[B]): Task[(Decision[B], BlockHistory[B])] = {
    evaluateRules(rules.toList, requestContext.initialBlockContext(this))
  }

  /**
   * Evaluates the block for a user metadata request. A single block can grant a user access to more than one group and
   * some rules (eg. the kibana ones) may resolve runtime variables like `@{acl:current_group}` differently for each of
   * them. To return correct, per-group metadata we:
   *   1. run only the authentication/authorization rules of the block to discover the user's available groups,
   *   2. re-run the whole block for each available group with that group set as the current one, so that every
   *      group-dependent rule is resolved against the right group,
   *   3. return one [[Decision]] (with its history) per group.
   * Each returned decision carries the group it was evaluated for as its `currentGroupId`, which the metadata
   * presentation layer uses to attribute the resolved, group-specific metadata to the right group.
   * Blocks without an authentication/authorization rule (and so without groups) are evaluated just once.
   */
  def evaluateMetadata(requestContext: UserMetadataRequestContext.Aux[UserMetadataRequestBlockContext]): Task[NonEmptyList[(Decision[UserMetadataRequestBlockContext], BlockHistory[UserMetadataRequestBlockContext])]] = {
    if (containsAuthRule) {
      collectAvailableGroups(requestContext).flatMap {
        case Some(groups) => evaluateMetadataForEachGroup(requestContext, groups)
        case None => evaluateMetadataAsSingleDecision(requestContext)
      }
    } else {
      evaluateMetadataAsSingleDecision(requestContext)
    }
  }

  private def evaluateMetadataAsSingleDecision(requestContext: UserMetadataRequestContext.Aux[UserMetadataRequestBlockContext]) = {
    evaluate(requestContext).map { case (decision, history) => NonEmptyList.one((decision, history)) }
  }

  private def collectAvailableGroups(requestContext: UserMetadataRequestContext.Aux[UserMetadataRequestBlockContext]): Task[Option[NonEmptyList[Group]]] = {
    val authRules = rules.toList.filter {
      case _: Rule.AuthenticationRule => true
      case _: Rule.AuthorizationRule => true
      case _ => false
    }
    evaluateRules(authRules, requestContext.initialBlockContext(this)).map {
      case (Decision.Permitted(blockContext), _) => NonEmptyList.fromList(blockContext.blockMetadata.availableGroups.toList)
      case (Decision.Denied(_), _) => None
    }
  }

  private def evaluateMetadataForEachGroup(requestContext: UserMetadataRequestContext.Aux[UserMetadataRequestBlockContext],
                                           groups: NonEmptyList[Group]): Task[NonEmptyList[(Decision[UserMetadataRequestBlockContext], BlockHistory[UserMetadataRequestBlockContext])]] = {
    Task
      .sequence {
        groups.toList.map { group =>
          evaluateRules(rules.toList, requestContext.initialBlockContext(this).withBlockMetadata(_.withCurrentGroupId(group.id)))
        }
      }
      .map(NonEmptyList.fromListUnsafe)
  }

  private def evaluateRules[B <: BlockContext : BlockContextUpdater](rulesToCheck: List[Rule],
                                                                     initBlockContext: B): Task[(Decision[B], BlockHistory[B])] = {
    rulesToCheck
      .foldLeft(matched[B](Decision.Permitted(initBlockContext))) {
        case (currentResult, rule) =>
          for {
            previousRulesResult <- currentResult
            resultAfterRulesCheck <- previousRulesResult match {
              case Decision.Permitted(blockContext) =>
                checkRule(rule, blockContext)
              case r@Decision.Denied(_) =>
                mismatched(r)
            }
          } yield resultAfterRulesCheck
      }
      .run
      .map { case (history, result) =>
        val blockHistory = result match {
          case d@Decision.Permitted(_) => BlockHistory.Permitted(this, d, history)
          case d@Decision.Denied(_) => BlockHistory.Denied(this, d, history)
        }
        result -> blockHistory
      }
  }

  private lazy val containsAuthRule: Boolean =
    rules.exists {
      case _: Rule.AuthenticationRule => true
      case _: Rule.AuthorizationRule => true
      case _ => false
    }

  private def checkRule[B <: BlockContext : BlockContextUpdater](rule: Rule, blockContext: B) = {
    implicit val blockContextImpl: B = blockContext
    val ruleDecision = rule
      .check[B](blockContext)
      .recover { case e =>
        logger.error(s"${name.show}: ${rule.name.show} rule matching got an error ${e.getMessage}", e)
        val cause = rule match {
          case _: Rule.AuthenticationRule => Cause.AuthenticationFailed("Unexpected error")
          case _: Rule.AuthorizationRule => Cause.GroupsAuthorizationFailed("Unexpected error")
          case _: Rule.RegularRule => Cause.NotAuthorized
        }
        Decision.Denied[B](cause)
      }
    lift[B](ruleDecision)
      .flatTap { decision =>
        WriterT.tell(Vector(RuleHistory(rule.name, decision)))
      }
  }

  private def matched[B <: BlockContext](result: Decision.Permitted[B]): WriterT[Task, Vector[RuleHistory[B]], Decision[B]] =
    lift[B](Task.now(result))

  private def mismatched[B <: BlockContext](result: Decision.Denied[B]): WriterT[Task, Vector[RuleHistory[B]], Decision[B]] =
    lift[B](Task.now(result))

}

object Block {

  def createFrom(name: Name,
                 policy: Option[Policy],
                 verbosity: Option[Verbosity],
                 audit: Option[Audit],
                 rules: NonEmptyList[RuleDefinition[Rule]])
                (implicit loggingContext: LoggingContext): Either[BlocksLevelCreationError, Block] = {
    val sortedRules = rules.sorted
    BlockValidator.validate(name, sortedRules) match {
      case Validated.Valid(_) =>
        Right(createBlockInstance(name, policy, verbosity, audit, sortedRules))
      case Validated.Invalid(errors) =>
        implicit val validationErrorShow: Show[BlockValidationError] = blockValidationErrorShow(name)
        Left(BlocksLevelCreationError(Message(errors.toList.map(_.show).mkString("\n"))))
    }
  }

  private def createBlockInstance(name: Name,
                                  policy: Option[Policy],
                                  verbosity: Option[Verbosity],
                                  audit: Option[Audit],
                                  rules: NonEmptyList[RuleDefinition[Rule]])
                                 (implicit loggingContext: LoggingContext) =
    new Block(
      name = name,
      policy = policy.getOrElse(Block.Policy.Allow),
      verbosity = verbosity.getOrElse(Block.Verbosity.Info),
      audit = audit.getOrElse(Block.Audit.Enabled),
      rules = rules.map(_.rule)
    )

  final case class Name(value: String) extends AnyVal

  final case class RuleDefinition[T <: Rule](rule: T,
                                             variableUsage: VariableUsage[T],
                                             impersonationWarnings: ImpersonationWarningSupport[T])
  object RuleDefinition {
    def create[T <: Rule : VariableUsage : ImpersonationWarningSupport](rule: T): RuleDefinition[T] = {
      new RuleDefinition(
        rule,
        implicitly[VariableUsage[T]],
        implicitly[ImpersonationWarningSupport[T]]
      )
    }
  }

  sealed trait Policy
  object Policy {
    case object Allow extends Policy
    final case class Forbid(responseMessage: Option[String] = None) extends Policy

    implicit val eq: Eq[Policy] = Eq.fromUniversalEquals
  }

  sealed trait Verbosity
  object Verbosity {
    case object Info extends Verbosity
    case object Error extends Verbosity

    implicit val eq: Eq[Verbosity] = Eq.fromUniversalEquals
  }

  sealed trait Audit

  object Audit {
    case object Enabled extends Audit

    case object Disabled extends Audit

    implicit val eq: Eq[Audit] = Eq.fromUniversalEquals
  }

  private class Lifter[B <: BlockContext] {
    def apply[A](task: Task[A]): WriterT[Task, Vector[RuleHistory[B]], A] =
      WriterT.liftF[Task, Vector[RuleHistory[B]], A](task)
  }
  private object Lifter {
    def lift[B <: BlockContext]: Lifter[B] = new Lifter[B]()
  }
}
