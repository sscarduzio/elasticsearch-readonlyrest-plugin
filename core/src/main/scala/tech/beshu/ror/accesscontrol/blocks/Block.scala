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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
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

  def execute(requestContext: RequestContext): BlockResultWithHistory = {
    implicit val showHeader: Show[Header] = obfuscatedHeaderShow(loggingContext.obfuscatedHeaders)
    val initBlockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)
    rules
      .foldLeft(matched(initBlockContext)) {
        case (currentResult, rule) =>
          for {
            previousRulesResult <- currentResult
            newCurrentResult <- previousRulesResult match {
              case Matched(_, blockContext) =>
                val ruleResult = rule
                  .check(requestContext, blockContext)
                  .recover { case e =>
                    logger.error(s"${name.show}: ${rule.name.show} rule matching got an error ${e.getMessage}", e)
                    RuleResult.Rejected()
                  }
                lift(ruleResult)
                  .flatMap {
                    case result@RuleResult.Fulfilled(newBlockContext) =>
                      matched(newBlockContext)
                        .tell(Vector(HistoryItem(rule.name, result)))
                    case result: RuleResult.Rejected =>
                      mismatched(blockContext)
                        .tell(Vector(HistoryItem(rule.name, result)))
                  }
              case Mismatched(lastBlockContext) =>
                mismatched(lastBlockContext)
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
        case Success((_: Mismatched, history)) =>
          implicit val requestShow: Show[RequestContext] = RequestContext.show(None, None, Vector(history))
          logger.debug(s"$ANSI_YELLOW[${name.show}] the request matches no rules in this block: ${requestContext.show} $ANSI_RESET")
      }
  }

  private def matched[T <: BlockContext](blockContext: T): WriterT[Task, Vector[HistoryItem], ExecutionResult] =
    lift(Task.now(Matched(this, blockContext): ExecutionResult))

  private def mismatched[T <: BlockContext](blockContext: T): WriterT[Task, Vector[HistoryItem], ExecutionResult] =
    lift(Task.now(Mismatched(blockContext)))

  private def lift[T](task: Task[T]) =
    WriterT.liftF[Task, Vector[HistoryItem], T](task)

}

object Block {

  type BlockResultWithHistory = Task[(Block.ExecutionResult, History)]

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
  final case class History(block: Block.Name, items: Vector[HistoryItem], blockContext: BlockContext)
  final case class HistoryItem(rule: Rule.Name, result: RuleResult)

  sealed trait ExecutionResult {
    def blockContext: BlockContext
  }
  object ExecutionResult {
    final case class Matched(block: Block, override val blockContext: BlockContext) extends ExecutionResult
    final case class Mismatched(override val blockContext: BlockContext) extends ExecutionResult
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

}
