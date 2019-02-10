package tech.beshu.ror.acl

import cats.Show
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.AclLoggingDecorator.FinalState
import tech.beshu.ror.acl.blocks.Block.ExecutionResult
import tech.beshu.ror.acl.blocks.Block.Policy.{Allow, Forbid}
import tech.beshu.ror.acl.blocks.{Block, BlockContext}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.utils.TaskOps._
import tech.beshu.ror.{Constants, SerializationTool}

import scala.util.{Failure, Success}

class AclLoggingDecorator(underlying: Acl, serializationTool: Option[SerializationTool])
  extends Acl with Logging {

  override def handle(requestContext: RequestContext,
                      handler: AclHandler): Task[(Vector[Block.History], Block.ExecutionResult)] = {
    logger.debug(s"checking request: ${requestContext.id.show}")
    underlying
      .handle(requestContext, handler)
      .andThen {
        case Success((history, ExecutionResult.Matched(block, blockContext))) =>
          block.policy match {
            case Allow =>
              log(FinalState.Allowed, Some(block.verbosity), block.show, requestContext, Some(blockContext), history)
            case Forbid =>
              log(FinalState.Forbidden, Some(block.verbosity), block.show, requestContext, Some(blockContext), history)
          }
        case Success((history, ExecutionResult.Unmatched)) =>
          log(FinalState.Forbidden, None, "default", requestContext, None, history)
        case Failure(ex) =>
          if (handler.isNotFound(ex)) log(FinalState.Errored, None, "not found", requestContext, None, Vector.empty)
          else log(FinalState.Errored, None, "error", requestContext, None, Vector.empty)
      }
  }

  private def log(state: FinalState,
                  blockVerbosity: Option[Block.Verbosity],
                  reason: String,
                  requestContext: RequestContext,
                  blockContext: Option[BlockContext],
                  history: Vector[Block.History]): Unit = {
    if (!shouldSkipLog(state, blockVerbosity)) {
      implicit val requestShow: Show[RequestContext] = RequestContext.show(blockContext, history)
      logger.info {
        s"""${stateColor(state)}${state.show} by $reason req=${requestContext.show}${Constants.ANSI_RESET}"""
      }
      //todo:
      //serializationTool.foreach(_.submit(requestContext.id.value, null))
    }
  }

  private def shouldSkipLog(state: FinalState, blockVerbosity: Option[Block.Verbosity]) = {
    state == FinalState.Allowed && !blockVerbosity.contains(Block.Verbosity.Info)
  }

  private def stateColor(state: FinalState) = state match {
    case FinalState.Forbidden => Constants.ANSI_PURPLE
    case FinalState.Allowed => Constants.ANSI_CYAN
    case FinalState.Errored => Constants.ANSI_RED
    case FinalState.NotFound => Constants.ANSI_YELLOW
  }

}

object AclLoggingDecorator {

  private sealed trait FinalState
  private object FinalState {
    case object Forbidden extends FinalState
    case object Allowed extends FinalState
    case object Errored extends FinalState
    case object NotFound extends FinalState

    implicit val show: Show[FinalState] = Show.show {
      case Forbidden => "FORBIDDEN"
      case Allowed => "ALLOWED"
      case Errored => "ERRORED"
      case NotFound => "NOT_FOUND"
    }
  }

}
