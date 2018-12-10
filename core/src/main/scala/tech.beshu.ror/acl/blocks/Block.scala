package tech.beshu.ror.acl.blocks

import cats.data.{NonEmptyList, WriterT}
import cats.implicits._
import monix.cats._
import monix.eval.Task
import tech.beshu.ror.acl.blocks.Block.ExecutionResult
import tech.beshu.ror.acl.blocks.Block.ExecutionResult._
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.requestcontext.RequestContext

class Block(rules: NonEmptyList[Rule]) {

  type History = List[String]
  type BlockResultWithHistory = WriterT[Task, History, Block.ExecutionResult]

  def execute(context: RequestContext): BlockResultWithHistory = {
    rules.foldLeft(matched) {
      case (acc, rule) =>
        for {
          lastResult <- acc
          res <- lastResult match {
            case Matched =>
              lift(rule.`match`(context))
                .flatMap {
                  case true => matched.tell("matched" :: Nil)
                  case false => unmatched.tell("unmatched" :: Nil)
                }
            case Unmatched =>
              unmatched
          }
        } yield res
    }
  }

  private val unmatched = lift(Task.now(Block.ExecutionResult.Unmatched: ExecutionResult))

  private val matched = lift(Task.now(Block.ExecutionResult.Matched: ExecutionResult))

  private def lift[T](task: Task[T]) = WriterT.lift[Task, History, T](task)
}

object Block {

  sealed trait ExecutionResult
  object ExecutionResult {
    case object Matched extends ExecutionResult
    case object Unmatched extends ExecutionResult
  }

}
