/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
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