package tech.beshu.ror.acl.blocks.rules.utils

import tech.beshu.ror.acl.aDomain.IndexName
import tech.beshu.ror.acl.blocks.rules.impl.ZeroKnowledgeIndexFilter
import tech.beshu.ror.acl.blocks.rules.utils.ZeroKnowledgeIndexFilterScalaAdapter.CheckResult

import scala.collection.JavaConverters._

class ZeroKnowledgeIndexFilterScalaAdapter(underlying: ZeroKnowledgeIndexFilter) {

  def check(indices: Set[IndexName], matcher: Matcher): CheckResult = {
    val processedIndices: java.util.Set[String] = scala.collection.mutable.Set.empty[String].asJava
    val result = underlying.alterIndicesIfNecessaryAndCheck(
      indices.map(_.value).asJava,
      matcher.underlying,
      processedIndices.addAll _
    )
    if(result) CheckResult.Ok(processedIndices.asScala.map(IndexName.apply).toSet)
    else CheckResult.Failed
  }
}

object ZeroKnowledgeIndexFilterScalaAdapter {
  sealed trait CheckResult
  object CheckResult {
    final case class Ok(processedIndices: Set[IndexName]) extends CheckResult
    case object Failed extends CheckResult
  }
}
