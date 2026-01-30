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
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning.ImpersonationWarningSupport
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.users.LocalUsersContext.LocalUsersSupport
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage
import tech.beshu.ror.accesscontrol.factory.BlockValidator
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.BlocksLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.request.RequestContext
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
    val initBlockContext = requestContext.initialBlockContext
    rules
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

  private def checkRule[B <: BlockContext : BlockContextUpdater](rule: Rule, blockContext: B) = {
    implicit val blockContextImpl: B = blockContext
    val ruleDecision = rule
      .check[B](blockContext)
      .recover { case e =>
        logger.error(s"${name.show}: ${rule.name.show} rule matching got an error ${e.getMessage}", e)
        val cause = rule match {
          case rule: Rule.AuthenticationRule => Cause.AuthenticationFailed
          case rule: Rule.AuthorizationRule => Cause.GroupsAuthorizationFailed
          case rule: Rule.RegularRule => Cause.NotAuthorized
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
                                             localUsersSupport: LocalUsersSupport[T],
                                             impersonationWarnings: ImpersonationWarningSupport[T])
  object RuleDefinition {
    def create[T <: Rule : VariableUsage : LocalUsersSupport : ImpersonationWarningSupport](rule: T): RuleDefinition[T] = {
      new RuleDefinition(
        rule,
        implicitly[VariableUsage[T]],
        implicitly[LocalUsersSupport[T]],
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
