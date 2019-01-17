package tech.beshu.ror.acl.utils

import scala.util.Try

object ScalaExt {

  implicit class TryOps[T](val `try`: Try[T]) extends AnyVal {

    def getOr(mapEx: Throwable => T): T = `try`.fold(mapEx, identity)
  }

  implicit class ListOps[T](val list: List[T]) extends AnyVal {

    def findDuplicates: List[T] =
      list
        .groupBy(identity)
        .collect { case (x, List(_, _, _*)) => x }
        .toList
  }

}
