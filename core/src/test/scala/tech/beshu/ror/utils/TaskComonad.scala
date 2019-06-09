package tech.beshu.ror.utils

import cats.Comonad
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration._
import scala.language.postfixOps

class TaskComonad(timeout: FiniteDuration)
                 (implicit scheduler: Scheduler)
  extends Comonad[Task] {

  override def extract[A](x: Task[A]): A = x.runSyncUnsafe(timeout)

  override def coflatMap[A, B](fa: Task[A])(f: Task[A] => B): Task[B] = fa.map { a => f(Task.pure(a)) }

  override def map[A, B](fa: Task[A])(f: A => B): Task[B] = fa.map(f)
}

object TaskComonad {
  implicit val wait30SecTaskComonad: Comonad[Task] =
    new TaskComonad(30 seconds)(monix.execution.Scheduler.Implicits.global)
}