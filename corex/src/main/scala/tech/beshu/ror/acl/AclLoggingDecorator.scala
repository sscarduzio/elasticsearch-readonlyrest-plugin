package tech.beshu.ror.acl

import cats.Show
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.scala.Logging
import squants.information.Bytes
import tech.beshu.ror.acl.AclLoggingDecorator.FinalState
import tech.beshu.ror.acl.blocks.{Block, BlockContext}
import tech.beshu.ror.acl.blocks.Block.ExecutionResult
import tech.beshu.ror.acl.blocks.Block.Policy.{Allow, Forbid}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps.toRequestContextOps
import tech.beshu.ror.acl.utils.TaskOps._
import tech.beshu.ror.commons.Constants
import aDomain.Header
import show.logs._
import tech.beshu.ror.requestcontext.SerializationTool

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
      logger.info {
        s"""${stateColor(state)}${state.show} by $reason req=${resultShow(requestContext, blockContext, history)}${Constants.ANSI_RESET}"""
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

  private def resultShow(r: RequestContext, blockContext: Option[BlockContext], history: Vector[Block.History]) = {
    def stringifyLoggedUser = blockContext.flatMap(_.loggedUser) match {
      case Some(user) => s"${user.id.show}"
      case None => "[no basic auth header]"
    }

    def stringifyContentLength = {
      if (r.contentLength == Bytes(0)) "<N/A>"
      else if (logger.delegate.isEnabled(Level.DEBUG)) r.content
      else s"<OMITTED, LENGTH=${r.contentLength}> "
    }

    def stringifyIndices = {
      blockContext
        .toSet
        .flatMap { b: BlockContext => b.indices }
        .toList
        .map(_.show) match {
        case Nil => "<N/A>"
        case nel => nel.mkString(",")
      }
    }

    s"""{
       | ID:${r.id.show},
       | TYP:${r.`type`.show},
       | CGR:${r.currentGroup.show},
       | USR:$stringifyLoggedUser,
       | BRS:${r.headers.exists(_.name === Header.Name.userAgent)},
       | KDX:${blockContext.flatMap(_.kibanaIndex).getOrElse("null")},
       | ACT:${r.action.show},
       | OA:${r.remoteAddress.show},
       | XFF:${r.headers.find(_.name === Header.Name.xForwardedFor).map(_.value).getOrElse("null")},
       | DA:${r.localAddress.show},
       | IDX:$stringifyIndices,
       | MET:${r.method.show},
       | PTH:${r.uri.show},
       | CNT:$stringifyContentLength,
       | HDR:${r.headers.map(_.show).toList.sorted.mkString(", ")},
       | HIS:${history.map(_.show).mkString(", ")}
       | }""".stripMargin.replaceAll("\n", " ")
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
