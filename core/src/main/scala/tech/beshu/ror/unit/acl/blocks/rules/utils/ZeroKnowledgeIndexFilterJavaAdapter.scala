package tech.beshu.ror.unit.acl.blocks.rules.utils

import tech.beshu.ror.commons.aDomain.IndexName
import tech.beshu.ror.unit.acl.blocks.rules.impl.ZeroKnowledgeIndexFilter
import tech.beshu.ror.unit.acl.blocks.rules.utils.ZeroKnowledgeIndexFilterJavaAdapter.CheckResult

import scala.collection.JavaConverters._

class ZeroKnowledgeIndexFilterJavaAdapter(underlying: ZeroKnowledgeIndexFilter) {

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

object ZeroKnowledgeIndexFilterJavaAdapter {
  sealed trait CheckResult
  object CheckResult {
    final case class Ok(processedIndices: Set[IndexName]) extends CheckResult
    case object Failed extends CheckResult
  }
}
