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
package tech.beshu.ror.utils

import cats.Functor
import cats.data.{EitherT, NonEmptyList, NonEmptySet}
import cats.effect.{ContextShift, IO}
import cats.implicits.*
import eu.timepit.refined.api.Refined
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.util
import java.util.Base64
import scala.collection.immutable.SortedSet
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.language.{implicitConversions, postfixOps}
import scala.reflect.ClassTag
import scala.util.Try

object ScalaOps {

  implicit class IterableOnceOps[T](iterable: IterableOnce[T]) extends AnyVal {

    def mkStringOrEmptyString(start: String, sep: String, end: String): String = {
      if (iterable.iterator.isEmpty) ""
      else iterable.iterator.mkString(start, sep, end)
    }
  }

  implicit class TryOps[T](`try`: Try[T]) extends AnyVal {

    def getOr(mapEx: Throwable => T): T = `try`.fold(mapEx, identity)
  }

  implicit class JavaMapOps[K : ClassTag, V : ClassTag](map: java.util.Map[K, V]) {
    def asSafeMap: Map[K, V] = Option(map).map(_.asScala.toMap).getOrElse(Map.empty)

    def asSafeKeys: Set[K] = asSafeMap.keys.toCovariantSet

    def asSafeValues: Set[V] = asSafeMap.values.toCovariantSet
  }

  implicit class JavaMapFactoryMethod(mapObject: Map.type) extends AnyVal {
    def asEmptyJavaMap[K, V]: java.util.Map[K, V] = Map.empty[K, V].asJava
  }

  implicit class JavaListOps[T : ClassTag](val list: java.util.List[T]) {
    def asSafeList: List[T] = Option(list).map(_.asScala.toList).getOrElse(Nil)
  }

  implicit class JavaSetOps[T : ClassTag](set: java.lang.Iterable[T]) {
    def asSafeSet: Set[T] = Option(set).map(_.asScala.toCovariantSet).getOrElse(Set.empty)
  }

  implicit class ArrayOps[T : ClassTag](array: Array[T]) {
    def asSafeSet: Set[T] = asSafeList.toCovariantSet

    def asSafeList: List[T] = safeArray.toList

    private def safeArray = Option(array).getOrElse(Array.empty[T])
  }

  implicit class ListOps[T](list: List[T]) extends AnyVal {

    def findDuplicates: List[T] =
      findDuplicates(identity)

    def findDuplicates[S](provideComparatorOf: T => S): List[T] =
      list
        .groupBy(provideComparatorOf)
        .collect { case (_, List(fst, _, _*)) => fst }
        .toList
  }

  implicit class MapOps[K, V](map: Map[K, V]) {
    def asStringMap: Map[String, String] =
      map.collect {
        case (key: String, value: String) => (key, value)
      }
  }

  implicit class ListOfListOps[T](lists: List[List[T]]) extends AnyVal {

    def cartesian: List[List[T]] = {
      lists.foldRight(List(List.empty[T])) {
        case (xs, yss) =>
          for {
            x <- xs
            ys <- yss
          } yield x :: ys
      }
    }
  }

  implicit class NonEmptyListOfNonEmptyListOps[T](lists: NonEmptyList[NonEmptyList[T]]) extends AnyVal {

    def cartesian: NonEmptyList[NonEmptyList[T]] = {
      NonEmptyList.fromListUnsafe(new ListOfListOps(lists.map(_.toList).toList).cartesian.map(NonEmptyList.fromListUnsafe))
    }
  }

  implicit class ListOfEitherOps[A, B](either: List[Either[A, B]]) extends AnyVal {

    def partitionEither: (List[A], List[B]) = {
      either.partitionMap(identity)
    }
  }

  implicit val nonEmptyStringOrdering: Ordering[NonEmptyString] = Ordering.by(_.value)

  def value[F[_], A, B](eitherT: EitherT[F, A, B]): F[Either[A, B]] = eitherT.value

  def retry[T](task: Task[T]): Task[T] = {
    ScalaOps.retryBackoff(task, 5, 500 millis, 1)
  }

  def retryBackoff[A](source: Task[A],
                      maxRetries: Int,
                      firstDelay: FiniteDuration,
                      backOffScaler: Int): Task[A] = {
    retryBackoffEither[Nothing, A](source.map(Right(_)), maxRetries, firstDelay, backOffScaler)
      .map(_.getOrElse(throw new IllegalStateException("Impossible")))
  }

  def retryBackoffEither[E, A](source: Task[Either[E, A]],
                               maxRetries: Int,
                               firstDelay: FiniteDuration,
                               backOffScaler: Int): Task[Either[E, A]] = {
    def doRetry() = {
      retryBackoffEither(source, maxRetries - 1, firstDelay * backOffScaler, backOffScaler)
        .delayExecution(firstDelay)
    }

    source
      .flatMap {
        case right@Right(_) => Task.now(right)
        case Left(_) if maxRetries > 0 => doRetry()
        case Left(error) => Task.now(Left(error))
      }
      .onErrorHandleWith {
        case _: Exception if maxRetries > 0 => doRetry()
        case ex => Task.raiseError(ex)
      }
  }

  def repeat[A](maxRetries: Int, delay: FiniteDuration)(source: Task[A]): Task[Unit] = {
    source
      .delayExecution(delay)
      .flatMap { _ =>
        if (maxRetries > 0) repeat(maxRetries - 1, delay)(source)
        else Task.unit
      }
  }

  implicit def taskToIo[T](t: Task[T])
                          (implicit scheduler: Scheduler,
                           contextShift: ContextShift[IO]): IO[T] = {
    IO.fromFuture(IO(t.runToFuture))
  }

  implicit class AutoCloseableOps[A <: AutoCloseable](value: A) extends AnyVal {
    def bracket[B](convert: A => B): B = {
      try {
        convert(value)
      } finally {
        value.close()
      }
    }
  }

  implicit class AutoClosableMOps[A <: AutoCloseable, M[_]: Functor](value: M[A]) {
    def bracket[B](convert: A => B): M[B] = {
      import cats.implicits.*
      value.map(v => AutoCloseableOps(v).bracket(convert))
    }
  }

  implicit class NonEmptySetOps[T](value: NonEmptySet[T]) extends AnyVal {
    import cats.implicits.*

    def widen[S >: T : Ordering]: NonEmptySet[S] = NonEmptySet.fromSetUnsafe(SortedSet.empty[S] ++ value.toList.widen[S].toSet)
  }

  implicit class StringOps(value: String) extends AnyVal {
    def splitByFirst(char: Char): Option[(String, String)] = {
      value.split(char).toList match {
        case Nil => None
        case _ :: Nil => None
        case one :: _ => Some((one, value.substring(one.length + 1)))
      }
    }

    def splitBy(str: String, caseInsensitive: Boolean = false): (String, Option[String]) = {
      val normalizedValue = if (caseInsensitive) value.toLowerCase else value
      val normalizedStr = if (caseInsensitive) str.toLowerCase else str

      normalizedValue.indexOf(normalizedStr) match {
        case -1 =>
          (value, None)
        case idx =>
          val endOfStrIndex = idx + str.length
          (value.substring(0, idx), Some(if (endOfStrIndex < value.length) value.substring(endOfStrIndex) else ""))
      }
    }

    def decodeBase64: Option[String] = {
      Try(new String(Base64.getDecoder.decode(value), "UTF-8")).toOption
    }

    def safeNonEmpty: Option[NonEmptyString] = {
      Option(value).flatMap(NonEmptyString.unapply)
    }

    def oneLiner: String = value.stripMargin.replaceAll("\n", "")

    def addTrailingSlashIfNotPresent(): String = {
      if (value.endsWith("/")) value else s"$value/"
    }
  }

  implicit class PositiveFiniteDurationAdd(duration: PositiveFiniteDuration) extends AnyVal {

    def +(duration: PositiveFiniteDuration): PositiveFiniteDuration = {
      Refined.unsafeApply(this.duration.value + duration.value)
    }
  }
}
