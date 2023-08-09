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
package tech.beshu.ror.es.handler.request.context.types.utils

import cats.data.NonEmptyList
import org.elasticsearch.cluster.metadata.AliasMetadata
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.matchers.Matcher.Conversion
import tech.beshu.ror.accesscontrol.matchers.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.es.handler.request.context.types.utils.FilterableAliasesMap.AliasesMap
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.{CaseMappingEquality, StringCaseMapping}

import scala.jdk.CollectionConverters._
import scala.language.implicitConversions

class FilterableAliasesMap(val value: AliasesMap) extends AnyVal {

  def filterOutNotAllowedAliases(allowedAliases: NonEmptyList[ClusterIndexName]): AliasesMap = {
    filter(value.asSafeMap.toList, allowedAliases).toMap.asJava
  }

  private def filter(responseIndicesNadAliases: List[(String, java.util.List[AliasMetadata])],
                     allowedAliases: NonEmptyList[ClusterIndexName]) = {
    implicit val mapping: CaseMappingEquality[String] = StringCaseMapping.caseSensitiveEquality
    implicit val conversion = Conversion.from[AliasMetadata, String](_.alias())
    val matcher = MatcherWithWildcardsScalaAdapter.create(allowedAliases.toList.map(_.stringify))
    responseIndicesNadAliases
      .map { case (indexName, aliasesList) =>
        val filteredAliases = matcher.filter(aliasesList.asSafeList.toSet)
        (indexName, filteredAliases.toList.asJava)
      }
  }

}

object FilterableAliasesMap {
  type AliasesMap = java.util.Map[String, java.util.List[AliasMetadata]]

  implicit def toFilterableAliasesMap(map: AliasesMap): FilterableAliasesMap = new FilterableAliasesMap(map)
}