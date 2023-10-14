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
import org.elasticsearch.cluster.metadata.AliasMetaData
import org.elasticsearch.common.collect.ImmutableOpenMap
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.matchers.Matcher.Conversion
import tech.beshu.ror.utils.MatcherWithWildcardsScala
import tech.beshu.ror.es.handler.request.context.types.utils.FilterableAliasesMap.AliasesMap
import tech.beshu.ror.es.utils.EsCollectionsScalaUtils.ImmutableOpenMapOps
import tech.beshu.ror.utils.ScalaOps._


import scala.jdk.CollectionConverters._
import scala.language.implicitConversions

class FilterableAliasesMap(val value: AliasesMap) extends AnyVal {

  def filterOutNotAllowedAliases(allowedAliases: NonEmptyList[ClusterIndexName]): AliasesMap = {
    ImmutableOpenMapOps.from {
      filter(value.asSafeEntriesList, allowedAliases).toMap
    }
  }

  private def filter(responseIndicesNadAliases: List[(String, java.util.List[AliasMetaData])],
                     allowedAliases: NonEmptyList[ClusterIndexName]) = {
    implicit val conversion = Conversion.from[AliasMetaData, String](_.alias())
    val matcher = MatcherWithWildcardsScala.create(allowedAliases.toList.map(_.stringify))
    responseIndicesNadAliases
      .map { case (indexName, aliasesList) =>
        val filteredAliases = matcher.filter(aliasesList.asSafeList.toSet)
        (indexName, filteredAliases.toList.asJava)
      }
  }

}

object FilterableAliasesMap {
  type AliasesMap = ImmutableOpenMap[String, java.util.List[AliasMetaData]]

  implicit def toFilterableAliasesMap(map: AliasesMap): FilterableAliasesMap = new FilterableAliasesMap(map)
}
