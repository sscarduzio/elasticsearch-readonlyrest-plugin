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
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.audit.LoggingContext
import tech.beshu.ror.accesscontrol.blocks.Block.*
import tech.beshu.ror.accesscontrol.blocks.Block.ExecutionResult.{Matched, Mismatched}
import tech.beshu.ror.accesscontrol.blocks.Block.HistoryItem.RuleHistoryItem
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning.ImpersonationWarningSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.users.LocalUsersContext.LocalUsersSupport
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage
import tech.beshu.ror.accesscontrol.factory.BlockValidator
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.BlocksLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.implicits.*

class Block(val name: Name,
            val policy: Policy,
            val verbosity: Verbosity,
            val rules: NonEmptyList[Rule])
           (implicit val loggingContext: LoggingContext)
  extends Logging {

  import Lifter.*

  def execute[B <: BlockContext : BlockContextUpdater](requestContext: RequestContext.Aux[B]): BlockResultWithHistory[B] = {
    val initBlockContext = requestContext.initialBlockContext
    rules
      .foldLeft(matched[B](initBlockContext)) {
        case (currentResult, rule) =>
          for {
            previousRulesResult <- currentResult
            resultAfterRulesCheck <- previousRulesResult match {
              case Matched(_, blockContext) =>
                checkRule[B](rule, blockContext)
              case Mismatched(lastBlockContext) =>
                mismatched[B](lastBlockContext)
            }
          } yield resultAfterRulesCheck
      }
      .mapBoth { case (history, result) =>
        (History(name, history, result.blockContext), result)
      }
      .run
      .map(_.swap)
  }

  private def checkRule[B <: BlockContext : BlockContextUpdater](rule: Rule, blockContext: B) = {
    val ruleResult = rule
      .check[B](blockContext)
      .recover { case e =>
        logger.error(s"[${blockContext.requestContext.id.show}] ${name.show}: ${rule.name.show} rule matching got an error ${e.getMessage}", e)
        RuleResult.Rejected[B]()
      }
    lift[B](ruleResult)
      .flatMap {
        case result: RuleResult.Fulfilled[B] =>
          matched[B](result.blockContext)
            .tell(Vector(RuleHistoryItem(rule.name, result)))
        case result: RuleResult.Rejected[B] =>
          mismatched[B](blockContext)
            .tell(Vector(RuleHistoryItem(rule.name, result)))
      }
  }

  private def matched[B <: BlockContext](blockContext: B): WriterT[Task, Vector[HistoryItem[B]], ExecutionResult[B]] =
    lift[B](Task.now(ExecutionResult.matched(this, blockContext)))

  private def mismatched[B <: BlockContext](blockContext: B): WriterT[Task, Vector[HistoryItem[B]], ExecutionResult[B]] =
    lift[B](Task.now(ExecutionResult.mismatched(blockContext)))

}

object Block {

  private type BlockResultWithHistory[B <: BlockContext] = Task[(Block.ExecutionResult[B], History[B])]

  def createFrom(name: Name,
                 policy: Option[Policy],
                 verbosity: Option[Verbosity],
                 rules: NonEmptyList[RuleDefinition[Rule]])
                (implicit loggingContext: LoggingContext): Either[BlocksLevelCreationError, Block] = {
    val sortedRules = rules.sorted
    BlockValidator.validate(sortedRules) match {
      case Validated.Valid(_) =>
        Right(createBlockInstance(name, policy, verbosity, sortedRules))
      case Validated.Invalid(errors) =>
        implicit val validationErrorShow: Show[BlockValidationError] = blockValidationErrorShow(name)
        Left(BlocksLevelCreationError(Message(errors.toList.map(_.show).mkString("\n"))))
    }
  }

  private def createBlockInstance(name: Name,
                                  policy: Option[Policy],
                                  verbosity: Option[Verbosity],
                                  rules: NonEmptyList[RuleDefinition[Rule]])
                                 (implicit loggingContext: LoggingContext) =
    new Block(
      name,
      policy.getOrElse(Block.Policy.Allow),
      verbosity.getOrElse(Block.Verbosity.Info),
      rules.map(_.rule)
    )

  final case class Name(value: String) extends AnyVal
  final case class History[B <: BlockContext](block: Block.Name,
                                              items: Vector[HistoryItem[B]],
                                              blockContext: B)
  sealed trait HistoryItem[B <: BlockContext]
  object HistoryItem {
    final case class RuleHistoryItem[B <: BlockContext](rule: Rule.Name,
                                                        result: RuleResult[B])
      extends HistoryItem[B]
  }

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

  sealed trait ExecutionResult[B <: BlockContext] {
    def blockContext: B
  }
  object ExecutionResult {
    final case class Matched[B <: BlockContext](block: Block, override val blockContext: B)
      extends ExecutionResult[B]
    final case class Mismatched[B <: BlockContext](override val blockContext: B)
      extends ExecutionResult[B]

    def matched[B <: BlockContext](block: Block, blockContext: B): ExecutionResult[B] = Matched[B](block, blockContext)

    def mismatched[B <: BlockContext](blockContext: B): ExecutionResult[B] = Mismatched[B](blockContext)
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

  private class Lifter[B <: BlockContext] {
    def apply[A](task: Task[A]): WriterT[Task, Vector[HistoryItem[B]], A] =
      WriterT.liftF[Task, Vector[HistoryItem[B]], A](task)
  }
  private object Lifter {
    def lift[B <: BlockContext]: Lifter[B] = new Lifter[B]()
  }
}
