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
import tech.beshu.ror.accesscontrol.audit.sink.AuditSink
import tech.beshu.ror.accesscontrol.blocks.Block.*
import tech.beshu.ror.accesscontrol.blocks.Block.Audit.Enabled.PrecomputedAuditSinks.Available
import tech.beshu.ror.accesscontrol.blocks.Block.Audit.Enabled.{EnabledAuditSinks, PrecomputedAuditSinks}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning.ImpersonationWarningSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage
import tech.beshu.ror.accesscontrol.domain.SinkName
import tech.beshu.ror.accesscontrol.factory.BlockValidator
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.BlocksLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.request.{RequestContext, UserMetadataRequestContext}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import scala.language.implicitConversions

class Block(
    val name: Block.Name,
    val policy: Block.Policy,
    val rules: NonEmptyList[Rule],
    val audit: Block.Audit,
)(
    implicit val loggingContext: LoggingContext
) extends RequestIdAwareLogging {

  import Lifter.*

  def withResolvedAuditSinks(allSinks: List[AuditSink]): Block = {
    val newAudit = audit match {
      case Audit.Disabled =>
        Audit.Disabled
      case enabled @ Audit.Enabled(_, EnabledAuditSinks.All, _) =>
        enabled.copy(precomputedAuditSinks = Available(allSinks))
      case enabled @ Audit.Enabled(_, EnabledAuditSinks.Selected(on), _) =>
        enabled.copy(precomputedAuditSinks = Available(allSinks.filter(s => on.contains(s.name))))
      case enabled @ Audit.Enabled(_, EnabledAuditSinks.AllExcept(off), _) =>
        enabled.copy(precomputedAuditSinks = Available(allSinks.filter(s => !off.contains(s.name))))
    }
    new Block(name, policy, rules, newAudit)(loggingContext)
  }

  def evaluateForRegularRequest[B <: BlockContext: BlockContextUpdater](
      requestContext: RequestContext.Aux[B]
  ): Task[(Decision[B], BlockHistory[B])] = {
    evaluateRules(rules.toList, requestContext.initialBlockContext(this), Vector.empty[RuleHistory[B]])
  }

  /**
   * Evaluates the block for a user metadata request. A single block can grant a user access to more than one group and
   * some rules (eg. the kibana ones) may resolve runtime variables like `@{acl:current_group}` differently for each of
   * them. To return correct, per-group metadata we:
   *   1. run the authentication/authorization rules once - they form a fixed prefix of the block (see [[RuleOrdering]])
   *      and do not depend on the current group - to authenticate the user and discover the available groups,
   *   2. re-run only the remaining rules for each available group, starting from the block context produced in step 1
   *      with that group set as the current one, so that every group-dependent rule is resolved against the right group,
   *   3. return one [[Decision]] (with its history) per group.
   *      Each returned decision carries the group it was evaluated for as its `currentGroupId`, which the metadata
   *      presentation layer uses to attribute the resolved, group-specific metadata to the right group.
   *      Blocks without an authentication/authorization rule (and so without groups) are evaluated just once.
   */
  def evaluateForMetadataRequest(
      requestContext: UserMetadataRequestContext.Aux[UserMetadataRequestBlockContext]
  ): Task[NonEmptyList[(Decision[UserMetadataRequestBlockContext], BlockHistory[UserMetadataRequestBlockContext])]] = {
    if (containsAuthRule) {
      evaluateRules(
        authRules,
        requestContext.initialBlockContext(this),
        Vector.empty[RuleHistory[UserMetadataRequestBlockContext]]
      )
        .flatMap {
          case deniedResult @ (Decision.Denied(_), _) =>
            Task.now(NonEmptyList.one(deniedResult))
          case (Decision.Permitted(authBlockContext), authBlockHistory) =>
            evaluateRemainingRulesPerAvailableGroup(authBlockContext, authBlockHistory.history)
        }
    } else {
      evaluateForRegularRequest(requestContext)
        .map { case (decision, history) => NonEmptyList.one((decision, history)) }
    }
  }

  /**
   * Re-runs the non-authentication/authorization rules once per available group (or just once when the block grants no
   * group), reusing the block context already produced by the authentication/authorization rules instead of evaluating
   * them again. The history of those already-evaluated rules is prepended so each returned decision keeps the full
   * block history.
   */
  private def evaluateRemainingRulesPerAvailableGroup(
      authBlockContext: UserMetadataRequestBlockContext,
      authRulesHistory: Vector[RuleHistory[UserMetadataRequestBlockContext]]
  ): Task[NonEmptyList[(Decision[UserMetadataRequestBlockContext], BlockHistory[UserMetadataRequestBlockContext])]] = {
    NonEmptyList.fromList(authBlockContext.blockMetadata.availableGroups.toList) match {
      case Some(groups) =>
        Task
          .sequence {
            groups.toList.map { group =>
              evaluateRules(
                rulesToCheck = regularRules,
                initBlockContext = authBlockContext.withBlockMetadata(_.withCurrentGroupId(group.id)),
                priorHistory = authRulesHistory
              )
            }
          }
          .map(NonEmptyList.fromListUnsafe)
      case None =>
        evaluateRules(regularRules, authBlockContext, priorHistory = authRulesHistory)
          .map(NonEmptyList.one)
    }
  }

  private def evaluateRules[B <: BlockContext: BlockContextUpdater](
      rulesToCheck: List[Rule],
      initBlockContext: B,
      priorHistory: Vector[RuleHistory[B]]
  ): Task[(Decision[B], BlockHistory[B])] = {
    // Recursion instead of a fold: a Denied decision returns immediately, skipping the per-rule
    // wrapping of the remaining rules (which never run and add no history anyway).
    def checkRules(rules: List[Rule], blockContext: B): WriterT[Task, Vector[RuleHistory[B]], Decision[B]] =
      rules match {
        case Nil =>
          matched(Decision.Permitted(blockContext))
        case rule :: remainingRules =>
          checkRule(rule, blockContext).flatMap {
            case Decision.Permitted(newBlockContext) =>
              checkRules(remainingRules, newBlockContext)
            case denied @ Decision.Denied(_) =>
              mismatched(denied)
          }
      }

    checkRules(rulesToCheck, initBlockContext).run
      .map { case (history, result) =>
        val fullHistory = priorHistory ++ history
        val blockHistory = result match {
          case d @ Decision.Permitted(_) => BlockHistory.Permitted(this, d, fullHistory)
          case d @ Decision.Denied(_)    => BlockHistory.Denied(this, d, fullHistory)
        }
        result -> blockHistory
      }
  }

  private lazy val (authRules, regularRules) = rules.toList.partition(isAuthRule)
  private lazy val containsAuthRule: Boolean = authRules.nonEmpty

  private def isAuthRule(rule: Rule): Boolean = rule match {
    case _: Rule.AuthenticationRule => true
    case _: Rule.AuthorizationRule  => true
    case _                          => false
  }

  private def checkRule[B <: BlockContext: BlockContextUpdater](rule: Rule, blockContext: B) = {
    implicit val blockContextImpl: B = blockContext
    val ruleDecision = rule
      .check[B](blockContext)
      .recover { case e =>
        logger.error(s"${name.show}: ${rule.name.show} rule matching got an error ${e.getMessage}", e)
        val cause = rule match {
          case _: Rule.AuthenticationRule => Cause.AuthenticationFailed("Unexpected error")
          case _: Rule.AuthorizationRule  => Cause.GroupsAuthorizationFailed("Unexpected error")
          case _: Rule.RegularRule        => Cause.NotAuthorized
        }
        Decision.Denied[B](cause)
      }
    lift[B](ruleDecision)
      .flatTap { decision =>
        WriterT.tell(Vector(RuleHistory(rule.name, decision)))
      }
  }

  private def matched[B <: BlockContext](
      result: Decision.Permitted[B]
  ): WriterT[Task, Vector[RuleHistory[B]], Decision[B]] =
    lift[B](Task.now(result))

  private def mismatched[B <: BlockContext](
      result: Decision.Denied[B]
  ): WriterT[Task, Vector[RuleHistory[B]], Decision[B]] =
    lift[B](Task.now(result))

}

object Block {

  final case class Name(value: String) extends AnyVal

  final case class RuleDefinition[T <: Rule](
      rule: T,
      variableUsage: VariableUsage[T],
      impersonationWarnings: ImpersonationWarningSupport[T]
  )

  object RuleDefinition {

    def create[T <: Rule: VariableUsage: ImpersonationWarningSupport](rule: T): RuleDefinition[T] = {
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

  def createFrom(
      name: Block.Name,
      policy: Option[Block.Policy],
      audit: Option[Block.Audit],
      rules: NonEmptyList[Block.RuleDefinition[Rule]]
  )(
      implicit loggingContext: LoggingContext
  ): Either[BlocksLevelCreationError, Block] = {
    val sortedRules = rules.sorted
    BlockValidator.validate(name, sortedRules) match {
      case Validated.Valid(_) =>
        Right(
          new Block(
            name = name,
            policy = policy.getOrElse(Block.Policy.Allow),
            rules = sortedRules.map(_.rule),
            audit = audit.getOrElse(Block.Audit.Enabled()),
          )
        )
      case Validated.Invalid(errors) =>
        implicit val validationErrorShow: Show[BlockValidationError] = blockValidationErrorShow(name)
        Left(BlocksLevelCreationError(Message(errors.toList.map(_.show).mkString("\n"))))
    }
  }

  sealed trait Audit

  object Audit {

    final case class Enabled(
        logAllowedEvents: Boolean = true,
        enabledAuditSinks: EnabledAuditSinks = EnabledAuditSinks.All,
        // Blocks are decoded before the AuditingTool exists (which resolves sink names to actual AuditSink
        // instances), so we cannot resolve enabledAuditSinks into concrete sinks at decoding time.
        // Resolving them on every audit event would be too costly, so once the AuditingTool is available
        // (at ROR startup), the resolved sinks are precomputed and injected back into the Block here.
        // enabledAuditSinks remains the source of truth for the block's audit config; this field is just
        // a non-normalized cache of its resolution, kept to avoid recomputing it on every request.
        precomputedAuditSinks: PrecomputedAuditSinks = PrecomputedAuditSinks.NotAvailable,
    ) extends Audit

    object Enabled {
      sealed trait EnabledAuditSinks

      object EnabledAuditSinks {
        case object All extends EnabledAuditSinks

        final case class Selected(enabledSinks: Set[SinkName]) extends EnabledAuditSinks

        final case class AllExcept(disabledSinks: Set[SinkName]) extends EnabledAuditSinks
      }

      sealed trait PrecomputedAuditSinks

      object PrecomputedAuditSinks {
        case object NotAvailable extends PrecomputedAuditSinks

        final case class Available(auditSinks: List[AuditSink]) extends PrecomputedAuditSinks
      }

    }

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
