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
  def indexListText: NormalizedIndexList
  def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
}

object EsqlIndexTable {

  final case class SourceCommand private (
      indexListText: NormalizedIndexList,
      requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ) extends EsqlIndexTable {

    lazy val writtenPattern: PatternsMatcher[ClusterIndexName] =
      PatternsMatcher.create(requestedIndices.includedOnly)
  }

  object SourceCommand {
    def parse(indexListText: NormalizedIndexList): Option[SourceCommand] =
      requestedIndicesIn(indexListText).map(SourceCommand(indexListText, _))
  }

  final case class LookupJoin private (indexListText: NormalizedIndexList, index: ClusterIndexName)
      extends EsqlIndexTable {
    override def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]] =
      NonEmptyList.one(RequestedIndex(index, excluded = false))
  }

  object LookupJoin {

    def parse(indexListText: NormalizedIndexList): Option[LookupJoin] =
      requestedIndicesIn(indexListText).collect {
        case NonEmptyList(onlyOne, Nil) if !onlyOne.excluded => LookupJoin(indexListText, onlyOne.name)
      }

  }

  def requestedIndicesOf(tables: NonEmptyList[EsqlIndexTable]): Set[RequestedIndex[ClusterIndexName]] =
    tables.toList.flatMap(_.requestedIndices.toList).toCovariantSet

  private def requestedIndicesIn(
      indexListText: NormalizedIndexList
  ): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    NonEmptyList.fromList(
      indexListText.value.split(',').asSafeList.filter(_.nonEmpty).flatMap(RequestedIndex.fromString)
    )

}
