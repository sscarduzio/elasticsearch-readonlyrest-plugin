package tech.beshu.ror.accesscontrol.blocks.rules.utils

import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances._
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

class IndicesMatcher(indices: Set[IndexName]) {

  val availableIndicesMatcher: Matcher = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(
    indices.map(implicitly[StringTNaturalTransformation[IndexName]].toAString).asJava
  ))

  def filterIndices(indices: Set[IndexName]): Set[IndexName] = availableIndicesMatcher.filter(indices)

  def `match`(value: IndexName): Boolean = availableIndicesMatcher.`match`(value)

  def contains(str: String): Boolean = availableIndicesMatcher.contains(str)
}

object IndicesMatcher {
  def create(indices: Set[IndexName]): IndicesMatcher = {
    new IndicesMatcher(indices)
  }
}