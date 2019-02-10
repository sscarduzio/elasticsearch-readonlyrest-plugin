package tech.beshu.ror.acl.blocks

import cats.data.{NonEmptyList, WriterT}
import cats.implicits._
import cats.{Eq, Show}
import org.apache.logging.log4j.scala.Logging
import monix.eval.Task
import tech.beshu.ror.acl.blocks.Block.ExecutionResult.{Matched, Unmatched}
import tech.beshu.ror.acl.blocks.Block.{BlockResultWithHistory, ExecutionResult, History, HistoryItem, Name, Policy, Verbosity}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.utils.TaskOps._
import tech.beshu.ror.Constants.{ANSI_CYAN, ANSI_RESET, ANSI_YELLOW}

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
                      unmatched
                        .tell(Vector(HistoryItem(rule.name, matched = false)))
                  }
              case Unmatched =>
                unmatched
            }
          } yield newCurrentResult
      }
      .mapWritten(History(name, _))
      .run
      .map(_.swap)
      .andThen {
        case Success((Matched(_, _), _)) =>
          val block: Block = this
          logger.debug(s"${ANSI_CYAN}matched ${block.show}$ANSI_RESET")
        case Success((Unmatched, history)) =>
          implicit val requestShow: Show[RequestContext] = RequestContext.show(None, Vector(history))
          logger.debug(s"$ANSI_YELLOW[${name.show}] the request matches no rules in this block: ${requestContext.show} $ANSI_RESET")
      }
  }

  private def matched[T <: BlockContext](blockContext: T) = lift(Task.now(Matched(this, blockContext): ExecutionResult))

  private val unmatched = lift(Task.now(Unmatched: ExecutionResult))

  private def lift[T](task: Task[T]) = WriterT.liftF[Task, Vector[HistoryItem], T](task)
}

object Block {

  type BlockResultWithHistory = Task[(Block.ExecutionResult, History)]

  final case class Name(value: String) extends AnyVal
  object Name {
    implicit val show: Show[Name] = Show.show(_.value)
  }

  final case class History(block: Block.Name, items: Vector[HistoryItem])
  object History {
    implicit val show: Show[History] = Show.show { h =>
      s"""[${h.block.show}]->[${h.items.map(_.show).mkString(", ")}]"""
    }
  }

  final case class HistoryItem(rule: Rule.Name, matched: Boolean)
  object HistoryItem {
    implicit val show: Show[HistoryItem] = Show.show { hi => s"${hi.rule.show}->${hi.matched}"}
  }

  sealed trait ExecutionResult
  object ExecutionResult {
    final case class Matched(block: Block, context: BlockContext) extends ExecutionResult
    case object Unmatched extends ExecutionResult
  }

  sealed trait Policy
  object Policy {
    case object Allow extends Policy
    case object Forbid extends Policy

    implicit val eq: Eq[Policy] = Eq.fromUniversalEquals
    implicit val show: Show[Policy] = Show.show {
      case Allow => "ALLOW"
      case Forbid => "FORBID"
    }
  }

  sealed trait Verbosity
  object Verbosity {
    case object Info extends Verbosity
    case object Error extends Verbosity
  }

  implicit val blockShow: Show[Block] = Show.show { b =>
    s"{ name: '${b.name.show}', policy: ${b.policy.show}, rules: [${b.rules.map(_.name.show).toList.mkString(",")}]"
  }
}
