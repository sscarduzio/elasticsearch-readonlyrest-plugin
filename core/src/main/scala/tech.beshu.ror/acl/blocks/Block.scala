package tech.beshu.ror.acl.blocks

import cats.data.{NonEmptyList, WriterT}
import cats.implicits._
import cats.{Eq, Show}
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task
import tech.beshu.ror.acl.blocks.Block.ExecutionResult._
import tech.beshu.ror.acl.blocks.Block._
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContext._
import tech.beshu.ror.acl.utils.TaskOps._
import tech.beshu.ror.commons.Constants.{ANSI_CYAN, ANSI_RESET, ANSI_YELLOW}

import scala.util.Success

class Block(val name: Name,
            val policy: Policy,
            val rules: NonEmptyList[Rule])
  extends StrictLogging {

  def execute(requestContext: RequestContext): BlockResultWithHistory = {
    val initBlockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)
    rules
      .foldLeft(matched(initBlockContext)) {
        case (acc, rule) =>
          for {
            lastResult <- acc
            res <- lastResult match {
              case Matched(blockContext) =>
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
          } yield res
      }
      .mapWritten(History(name, _))
      .run
      .map(_.swap)
      .andThen {
        case Success((Matched(_), _)) =>
          val block: Block = this
          logger.debug(s"${ANSI_CYAN}matched ${block.show}$ANSI_RESET")
        case Success((Unmatched, _)) =>
          logger.debug(s"$ANSI_YELLOW[${name.show}] the request matches no rules in this block: ${requestContext.show} $ANSI_RESET")
      }
  }

  private def matched[T <: BlockContext](blockContext: T) = lift(Task.now(Matched(blockContext): ExecutionResult))

  private val unmatched = lift(Task.now(Unmatched: ExecutionResult))

  private def lift[T](task: Task[T]) = WriterT.liftF[Task, Vector[HistoryItem], T](task)
}

object Block {

  type BlockResultWithHistory = Task[(Block.ExecutionResult, History)]

  final case class Name(value: String) extends AnyVal
  final case class History(block: Block.Name, items: Vector[HistoryItem])
  final case class HistoryItem(rule: Rule.Name, matched: Boolean)

  object Name {
    implicit val show: Show[Name] = Show.show(_.value)
  }

  sealed trait ExecutionResult
  object ExecutionResult {
    final case class Matched(context: BlockContext) extends ExecutionResult
    case object Unmatched extends ExecutionResult
  }

  sealed trait Policy
  object Policy {
    case object Allow extends Policy
    case object Forbid extends Policy

    implicit val eq: Eq[Policy] = Eq.fromUniversalEquals
    implicit val show: Show[Policy] = Show.fromToString
  }

  implicit val blockShow: Show[Block] = Show.show { b =>
    s"{ name: '${b.name.show}', policy: { ${b.policy.show}, rules: ${b.rules.map(_.name.show).toList.mkString(",")} }"
  }
}
