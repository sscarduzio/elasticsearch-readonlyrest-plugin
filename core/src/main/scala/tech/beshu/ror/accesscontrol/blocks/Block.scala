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
import cats.implicits._
import cats.{Eq, Show}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants.{ANSI_CYAN, ANSI_RESET, ANSI_YELLOW}
import tech.beshu.ror.accesscontrol.blocks.Block.ExecutionResult.{Matched, Mismatched}
import tech.beshu.ror.accesscontrol.blocks.Block._
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RuleResult, RuleWithVariableUsageDefinition}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.factory.BlockValidator
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.BlocksLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.logging.LoggingContext
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.utils.TaskOps._

import scala.util.Success

class Block(val name: Name,
            val policy: Policy,
            val verbosity: Verbosity,
            val rules: NonEmptyList[Rule])
           (implicit loggingContext: LoggingContext)
  extends Logging {

  import Lifter._

  def execute[B <: BlockContext : BlockContextUpdater](requestContext: RequestContext.Aux[B]): BlockResultWithHistory[B] = {
    implicit val showHeader: Show[Header] = obfuscatedHeaderShow(loggingContext.obfuscatedHeaders)
    val initBlockContext = requestContext.initialBlockContext
    rules
      .foldLeft(matched[B](initBlockContext)) {
        case (currentResult, rule) =>
          for {
            previousRulesResult <- currentResult
            newCurrentResult <- previousRulesResult match {
              case Matched(_, blockContext) =>
                val ruleResult = rule
                  .check[B](blockContext)
                  .recover { case e =>
                    logger.error(s"${name.show}: ${rule.name.show} rule matching got an error ${e.getMessage}", e)
                    RuleResult.Rejected[B]()
                  }
                lift[B](ruleResult)
                  .flatMap {
                    case result: RuleResult.Fulfilled[B] =>
                      matched[B](result.blockContext)
                        .tell(Vector(HistoryItem(rule.name, result)))
                    case result: RuleResult.Rejected[B] =>
                      mismatched[B](blockContext)
                        .tell(Vector(HistoryItem(rule.name, result)))
                  }
              case Mismatched(lastBlockContext) =>
                mismatched[B](lastBlockContext)
            }
          } yield newCurrentResult
      }
      .mapBoth { case (history, result) =>
        (History(name, history, result.blockContext), result)
      }
      .run
      .map(_.swap)
      .andThen {
        case Success((Matched(_, blockContext), _)) =>
          val block: Block = this
          logger.debug(s"${ANSI_CYAN}matched ${block.show} { found: ${blockContext.show} }$ANSI_RESET")
        case Success((_: Mismatched[B], history)) =>
          implicit val requestShow: Show[RequestContext.Aux[B]] = RequestContext.show[B](None, None, Vector(history))
          logger.debug(s"$ANSI_YELLOW[${name.show}] the request matches no rules in this block: ${requestContext.show} $ANSI_RESET")
      }
  }

  private def matched[B <: BlockContext](blockContext: B): WriterT[Task, Vector[HistoryItem[B]], ExecutionResult[B]] =
    lift[B](Task.now(Matched(this, blockContext): ExecutionResult[B]))

  private def mismatched[B <: BlockContext](blockContext: B): WriterT[Task, Vector[HistoryItem[B]], ExecutionResult[B]] =
    lift[B](Task.now(Mismatched(blockContext)))

}

object Block {

  type BlockResultWithHistory[B <: BlockContext] = Task[(Block.ExecutionResult[B], History[B])]

  def createFrom(name: Name,
                 policy: Option[Policy],
                 verbosity: Option[Verbosity],
                 rules: NonEmptyList[RuleWithVariableUsageDefinition[Rule]])
                (implicit loggingContext: LoggingContext): Either[BlocksLevelCreationError, Block] = {
    val sortedRules = rules.sorted
    BlockValidator.validate(sortedRules) match {
      case Validated.Valid(_) => Right(createBlockInstance(name, policy, verbosity, sortedRules))
      case Validated.Invalid(errors) =>
        implicit val validationErrorShow: Show[BlockValidationError] = blockValidationErrorShow(name)
        Left(BlocksLevelCreationError(Message(errors.map(_.show).mkString_("\n"))))
    }
  }

  private def createBlockInstance(name: Name,
                                  policy: Option[Policy],
                                  verbosity: Option[Verbosity],
                                  rules: NonEmptyList[RuleWithVariableUsageDefinition[Rule]])
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
  final case class HistoryItem[B <: BlockContext](rule: Rule.Name,
                                                     result: RuleResult[B])

  sealed trait ExecutionResult[B <: BlockContext] {
    def blockContext: B
  }
  object ExecutionResult {
    final case class Matched[B <: BlockContext](block: Block,
                                                   override val blockContext: B)
      extends ExecutionResult[B]
    final case class Mismatched[B <: BlockContext](override val blockContext: B)
      extends ExecutionResult[B]
  }

  sealed trait Policy
  object Policy {
    case object Allow extends Policy
    case object Forbid extends Policy

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
