package tech.beshu.ror.acl.utils

import eu.timepit.refined.types.string.NonEmptyString

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
}
