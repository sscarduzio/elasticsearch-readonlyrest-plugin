package tech.beshu.ror.integration.utils

import cats.implicits._

object ScalaUtils {

  implicit class ListOps[T](val list: List[T]) extends AnyVal {

    def partitionByIndexMod2: (List[T], List[T]) = {
      list.zipWithIndex.partition(_._2 % 2 == 0).bimap(_.map(_._1), _.map(_._1))
    }
  }
}
