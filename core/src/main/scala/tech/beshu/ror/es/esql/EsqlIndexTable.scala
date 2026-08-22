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
package tech.beshu.ror.es.esql

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

sealed trait EsqlIndexTable {
  def indexListSpan: QueryTextSpan
  def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
}

object EsqlIndexTable {

  final case class SourceCommand private (
      indexListSpan: QueryTextSpan,
      requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ) extends EsqlIndexTable {

    lazy val writtenPattern: PatternsMatcher[ClusterIndexName] =
      PatternsMatcher.create(requestedIndices.includedOnly)
  }

  object SourceCommand {
    def parse(span: QueryTextSpan, reportedIndexList: String): Option[SourceCommand] =
      requestedIndicesIn(reportedIndexList).map(SourceCommand(span, _))
  }

  final case class LookupJoin private (indexListSpan: QueryTextSpan, index: ClusterIndexName) extends EsqlIndexTable {
    override def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]] =
      NonEmptyList.one(RequestedIndex(index, excluded = false))
  }

  object LookupJoin {

    def parse(span: QueryTextSpan, reportedIndexList: String): Option[LookupJoin] =
      requestedIndicesIn(reportedIndexList).collect {
        case NonEmptyList(onlyOne, Nil) if !onlyOne.excluded => LookupJoin(span, onlyOne.name)
      }

  }

  def requestedIndicesOf(tables: NonEmptyList[EsqlIndexTable]): Set[RequestedIndex[ClusterIndexName]] =
    tables.toList.flatMap(_.requestedIndices.toList).toCovariantSet

  private def requestedIndicesIn(
      reportedIndexList: String
  ): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    NonEmptyList.fromList(
      reportedIndexList.split(',').asSafeList.filter(_.nonEmpty).flatMap(RequestedIndex.fromString)
    )

}
