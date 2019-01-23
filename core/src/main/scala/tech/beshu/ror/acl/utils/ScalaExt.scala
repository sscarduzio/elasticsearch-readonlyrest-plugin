package tech.beshu.ror.acl.utils

import scala.util.Try

object ScalaExt {

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

}
