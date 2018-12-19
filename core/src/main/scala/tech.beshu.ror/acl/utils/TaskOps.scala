package tech.beshu.ror.acl.utils

import monix.eval.Task
import scala.util.{Failure, Success, Try}
import scala.language.implicitConversions

class TaskOps[T](val task: Task[T]) extends AnyVal {

  def andThen[U](pf: PartialFunction[Try[T], U]): Task[T] = {
    task
      .map { t =>
        pf.applyOrElse(Success(t), identity[Success[T]])
        t
      }
      .onErrorRecover { case e =>
        pf.applyOrElse(Failure(e), identity[Failure[Nothing]])
        throw e
      }
  }
}

object TaskOps {
  implicit def toTaskOps[T](task: Task[T]): TaskOps[T] = new TaskOps[T](task)
}
