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
import cats.syntax.traverse.*
import enumeratum.{Enum, EnumEntry}
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, IndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.es.esql.EsqlQueryIndicesReader.QueryIndices
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

private[esql] final case class TextSpan(start: Int, end: Int)

private[esql] sealed trait IndexPatternRole extends EnumEntry {

  def describe(indexPattern: String): String = this match {
    case IndexPatternRole.FromSource => indexPattern
    case IndexPatternRole.LookupJoin => s"LOOKUP JOIN ${indexPattern}"
  }

}

private[esql] object IndexPatternRole extends Enum[IndexPatternRole] {
  case object FromSource extends IndexPatternRole
  case object LookupJoin extends IndexPatternRole

  override val values: IndexedSeq[IndexPatternRole] = findValues

  def describedIndexListsOf(indices: QueryIndices): List[String] = {
    val relations = indices.withoutRepeats
    (relations.fromSources.map(pattern => FromSource.describe(pattern.reportedIndexList)) ++
      relations.lookupJoins.map(pattern => LookupJoin.describe(pattern.reportedIndexList))).sorted
  }

}

private[esql] sealed trait IndexListSyntax extends EnumEntry

private[esql] object IndexListSyntax extends Enum[IndexListSyntax] {
  case object BareIndexList extends IndexListSyntax
  case object PromqlIndexParameter extends IndexListSyntax

  override val values: IndexedSeq[IndexListSyntax] = findValues
}

private[esql] sealed trait LocatedIndexList {
  def span: TextSpan
  def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
}

private[esql] object LocatedIndexList {

  final case class SourceCommandIndices private (
      span: TextSpan,
      requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
      writtenAs: IndexListSyntax
  ) extends LocatedIndexList {

    lazy val writtenPattern: PatternsMatcher[ClusterIndexName] =
      PatternsMatcher.create(requestedIndices.includedOnly)
  }

  object SourceCommandIndices {

    def parse(span: TextSpan, indexPattern: String, writtenAs: IndexListSyntax): Option[SourceCommandIndices] =
      requestedIndicesIn(indexPattern).map(SourceCommandIndices(span, _, writtenAs))

  }

  final case class LookupJoinTarget private (span: TextSpan, index: ClusterIndexName) extends LocatedIndexList {
    override def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]] =
      NonEmptyList.one(RequestedIndex(index, excluded = false))
  }

  object LookupJoinTarget {

    /** A join reads one index, named in full: ES resolves neither a wildcard nor a remote cluster here. */
    def parse(span: TextSpan, indexPattern: String): Option[LookupJoinTarget] =
      requestedIndicesIn(indexPattern).collect {
        case NonEmptyList(RequestedIndex(index @ ClusterIndexName.Local(_: IndexName.Full), false), Nil) =>
          LookupJoinTarget(span, index)
      }

  }

  def requestedIndicesOf(indexLists: NonEmptyList[LocatedIndexList]): Set[RequestedIndex[ClusterIndexName]] =
    indexLists.toList.flatMap(_.requestedIndices.toList).toCovariantSet

  /** All of them or none: an entry ROR cannot read is an index it would leave the ACL unaware of. */
  private def requestedIndicesIn(indexPattern: String): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    indexPattern
      .split(',')
      .asSafeList
      .filter(_.nonEmpty)
      .traverse(RequestedIndex.fromString)
      .flatMap(NonEmptyList.fromList)

}

private[esql] final case class ReplacedQuery(query: String, intendedIndexLists: List[String]) {

  /** Held to what ES reads back out of the rewrite - the only thing saying which indices it will really run against. */
  def checkedAgainst(esIndices: QueryIndices): Either[Rejection, String] = {
    val read = IndexPatternRole.describedIndexListsOf(esIndices)
    Either.cond(
      test = intendedIndexLists == read,
      right = query,
      left = Rejection.SubstitutionNotConfirmed(intendedIndexLists, read)
    )
  }

}
