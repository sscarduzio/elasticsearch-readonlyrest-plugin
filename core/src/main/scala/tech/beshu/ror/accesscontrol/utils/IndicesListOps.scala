package tech.beshu.ror.accesscontrol.utils

import tech.beshu.ror.accesscontrol.domain.IndexName

import scala.language.implicitConversions

class IndicesListOps(val indices: List[IndexName]) extends AnyVal {

  def randomNonexistentIndex(): IndexName = {
    val foundIndex = indices.find(_.hasWildcard) orElse indices.headOption
    foundIndex match {
      case Some(indexName) if indexName.isClusterIndex => IndexName.randomNonexistentIndex2(
        indexName.value.value.replace(":", "_") // we don't want to call remote cluster
      )
      case Some(indexName) => IndexName.randomNonexistentIndex2(indexName.value.value)
      case None => IndexName.randomNonexistentIndex2()
    }
  }

}

object IndicesListOps {
  implicit def toOps(indices: List[IndexName]): IndicesListOps = new IndicesListOps(indices)
}
