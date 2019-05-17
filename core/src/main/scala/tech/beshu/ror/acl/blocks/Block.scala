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
package tech.beshu.ror.acl.blocks

import cats.data.{NonEmptyList, WriterT}
import cats.implicits._
import cats.{Eq, Show}
import org.apache.logging.log4j.scala.Logging
import monix.eval.Task
import tech.beshu.ror.acl.blocks.Block.ExecutionResult.{Matched, Unmatched}
import tech.beshu.ror.acl.blocks.Block._
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.utils.TaskOps._
import tech.beshu.ror.Constants.{ANSI_CYAN, ANSI_RESET, ANSI_YELLOW}
import tech.beshu.ror.acl.show.logs._

import scala.util.Success

class Block(val name: Name,
            val policy: Policy,
            val verbosity: Verbosity,
            val rules: NonEmptyList[Rule])
  extends Logging {

  def execute(requestContext: RequestContext): BlockResultWithHistory = {
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
                    RuleResult.Rejected
                  }
                lift(ruleResult)
                  .flatMap {
                    case RuleResult.Fulfilled(newBlockContext) =>
                      matched(newBlockContext)
                        .tell(Vector(HistoryItem(rule.name, matched = true)))
                    case RuleResult.Rejected =>
                      unmatched(blockContext)
                        .tell(Vector(HistoryItem(rule.name, matched = false)))
                  }
              case Unmatched(lastBlockContext) =>
                unmatched(lastBlockContext)
            }
          } yield newCurrentResult
      }
      .mapBoth { case (history, result) =>
        (History(name, history, result.blockContext), result)
      }
      .run
      .map(_.swap)
      .andThen {
        case Success((Matched(_, _), _)) =>
          val block: Block = this
          logger.debug(s"${ANSI_CYAN}matched ${block.show}$ANSI_RESET")
        case Success((Unmatched(_), history)) =>
          implicit val requestShow: Show[RequestContext] = RequestContext.show(None, Vector(history))
          logger.debug(s"$ANSI_YELLOW[${name.show}] the request matches no rules in this block: ${requestContext.show} $ANSI_RESET")
      }
  }

  private def matched[T <: BlockContext](blockContext: T): WriterT[Task, Vector[HistoryItem], ExecutionResult] =
    lift(Task.now(Matched(this, blockContext): ExecutionResult))

  private def unmatched[T <: BlockContext](blockContext: T): WriterT[Task, Vector[HistoryItem], ExecutionResult] =
    lift(Task.now(Unmatched(blockContext)))

  private def lift[T](task: Task[T]) =
    WriterT.liftF[Task, Vector[HistoryItem], T](task)

}

object Block {

  type BlockResultWithHistory = Task[(Block.ExecutionResult, History)]

  final case class Name(value: String) extends AnyVal
  final case class History(block: Block.Name, items: Vector[HistoryItem], blockContext: BlockContext)
  final case class HistoryItem(rule: Rule.Name, matched: Boolean)

  sealed trait ExecutionResult {
    def blockContext: BlockContext
  }
  object ExecutionResult {
    final case class Matched(block: Block, override val blockContext: BlockContext) extends ExecutionResult
    final case class Unmatched(override val blockContext: BlockContext) extends ExecutionResult
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
