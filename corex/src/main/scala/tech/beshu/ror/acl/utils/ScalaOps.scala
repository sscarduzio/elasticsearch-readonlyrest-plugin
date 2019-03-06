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
package tech.beshu.ror.acl.utils

import cats.data.EitherT
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task

import scala.concurrent.duration._
import scala.language.{higherKinds, postfixOps}
import scala.util.Try

object ScalaOps {

  implicit class TryOps[T](val `try`: Try[T]) extends AnyVal {

    def getOr(mapEx: Throwable => T): T = `try`.fold(mapEx, identity)
  }

  implicit class ListOps[T](val list: List[T]) extends AnyVal {

    def findDuplicates: List[T] =
      findDuplicates(identity)

    def findDuplicates[S](provideComparatorOf: T => S): List[T] =
      list
        .groupBy(provideComparatorOf)
        .collect { case (_, List(fst, _, _*)) => fst }
        .toList
  }

  implicit class ListOfEitherOps[A, B](val either: List[Either[A, B]]) extends AnyVal {

    def partitionEither: (List[A], List[B]) = {
      val (lefts, rights) = either.partition(_.isLeft)
      (lefts.map(_.left.get), rights.map(_.right.get))
    }
  }

  implicit val nonEmptyStringOrdering: Ordering[NonEmptyString] = Ordering.by(_.value)

  def value[F[_], A, B](eitherT: EitherT[F, A, B]):  F[Either[A, B]] = eitherT.value

  def retryBackoff[A](source: Task[A],
                      maxRetries: Int, firstDelay: FiniteDuration): Task[A] = {

    source.onErrorHandleWith {
      case ex: Exception =>
        if (maxRetries > 0)
          retryBackoff(source, maxRetries-1, firstDelay*2)
            .delayExecution(firstDelay)
        else
          Task.raiseError(ex)
    }
  }

  def retry[T](task: Task[T]): Task[T] = {
    ScalaOps.retryBackoff(task, 5, 500 millis)
  }
}
