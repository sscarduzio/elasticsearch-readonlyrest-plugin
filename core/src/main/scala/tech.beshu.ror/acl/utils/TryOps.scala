package tech.beshu.ror.acl.utils

import scala.language.implicitConversions
import scala.util.Try

class TryOps[T](`try`: Try[T]) {

  def getOr(mapEx: Throwable => T): T = `try`.fold(mapEx, identity)
}

object TryOps {
  implicit def toTryOps[T](`try`: Try[T]): TryOps[T] = new TryOps[T](`try`)
}