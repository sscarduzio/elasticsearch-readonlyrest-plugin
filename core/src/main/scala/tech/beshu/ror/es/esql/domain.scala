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
import tech.beshu.ror.utils.ScalaOps.*

private[esql] final case class TextSpan(start: Int, end: Int)

private[esql] sealed trait IndexListSyntax extends EnumEntry

private[esql] object IndexListSyntax extends Enum[IndexListSyntax] {
  case object BareIndexList extends IndexListSyntax
  case object PromqlIndexParameter extends IndexListSyntax

  override val values: IndexedSeq[IndexListSyntax] = findValues
}

private[esql] sealed trait LocatedIndexList {

  def span: TextSpan

  def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]

  def describe: String

}

private[esql] object LocatedIndexList {

  final case class SourceCommandIndices private (
      span: TextSpan,
      reportedIndexList: String,
      requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
      writtenAs: IndexListSyntax
  ) extends LocatedIndexList {

    override def describe: String = reportedIndexList

    lazy val writtenPattern: PatternsMatcher[ClusterIndexName] =
      PatternsMatcher.create(requestedIndices.includedOnly)
  }

  object SourceCommandIndices {

    def parse(span: TextSpan, reportedIndexList: String, writtenAs: IndexListSyntax): Option[SourceCommandIndices] =
      requestedIndicesIn(reportedIndexList)
        .map(SourceCommandIndices(span, reportedIndexList, _, writtenAs))

  }

  final case class LookupJoinTarget private (
      span: TextSpan,
      reportedIndexList: String,
      index: ClusterIndexName
  ) extends LocatedIndexList {

    override def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]] =
      NonEmptyList.one(RequestedIndex(index, excluded = false))

    override def describe: String = s"LOOKUP JOIN ${reportedIndexList}"
  }

  object LookupJoinTarget {

    /** A join reads one index, named in full: ES resolves neither a wildcard nor a remote cluster here. */
    def parse(span: TextSpan, reportedIndexList: String): Option[LookupJoinTarget] =
      requestedIndicesIn(reportedIndexList).collect {
        case NonEmptyList(RequestedIndex(index @ ClusterIndexName.Local(_: IndexName.Full), false), Nil) =>
          LookupJoinTarget(span, reportedIndexList, index)
      }

  }

  /** All of them or none: an entry ROR cannot read is an index it would leave the ACL unaware of. */
  private def requestedIndicesIn(indexPattern: String): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    indexPattern
      .split(',')
      .asSafeList
      .filter(_.nonEmpty)
      .traverse(RequestedIndex.fromString)
      .flatMap(NonEmptyList.fromList)

}
