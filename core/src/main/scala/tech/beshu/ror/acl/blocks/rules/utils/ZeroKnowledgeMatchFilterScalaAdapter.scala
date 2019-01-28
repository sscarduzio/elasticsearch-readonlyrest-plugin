package tech.beshu.ror.acl.blocks.rules.utils

import tech.beshu.ror.acl.aDomain.IndexName
import tech.beshu.ror.acl.blocks.rules.impl.ZeroKnowledgeMatchFilter
import tech.beshu.ror.acl.blocks.rules.utils.ZeroKnowledgeMatchFilterScalaAdapter.AlterResult

import scala.collection.JavaConverters._

class ZeroKnowledgeMatchFilterScalaAdapter {

  def alterIndicesIfNecessary(indices: Set[IndexName], matcher: Matcher): AlterResult = {
    Option(ZeroKnowledgeMatchFilter.alterIndicesIfNecessary(
      indices.map(_.value).asJava,
      matcher.underlying
    )) match {
      case Some(alteredIndices) => AlterResult.Altered(alteredIndices.asScala.map(IndexName.apply).toSet)
      case None => AlterResult.NotAltered
    }
  }
}

object ZeroKnowledgeMatchFilterScalaAdapter {
  sealed trait AlterResult
  object AlterResult {
    case object NotAltered extends AlterResult
    final case class Altered(indices: Set[IndexName]) extends AlterResult
  }
}
