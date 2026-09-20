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
package tech.beshu.ror.es.sql

import cats.data.NonEmptyList
import cats.syntax.traverse.*
import enumeratum.{Enum, EnumEntry}
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

private[sql] final case class TextSpan(start: Int, end: Int)

/** A 1-based line and a 0-based column, the way Elasticsearch reports them. */
final case class SourceLocation(line: Int, column: Int)

private[sql] final case class TableInQuery(
    reportedIndexList: String,
    writtenAt: SourceLocation,
    writtenText: String
)

private[sql] sealed trait CommandSelector

private[sql] object CommandSelector {

  case object NotIndexRelated extends CommandSelector

  case object AppendableIndexList extends CommandSelector

  final case class LiteralIndexList(indexList: String) extends CommandSelector

  /** The wildcard is ES's reading of the pattern, so it is nowhere in the query text - the whole clause goes. */
  final case class MatchingPattern(indexNameWildcard: String, commandName: String) extends CommandSelector

  final case class CannotNarrow(commandName: String) extends CommandSelector

}

private[sql] sealed trait IndexListSyntax extends EnumEntry

private[sql] object IndexListSyntax extends Enum[IndexListSyntax] {

  case object InQueryText extends IndexListSyntax

  case object AppendedToQuery extends IndexListSyntax

  override val values: IndexedSeq[IndexListSyntax] = findValues

}

private[sql] final case class LocatedIndexList(
    span: TextSpan,
    requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
    writtenAs: IndexListSyntax
)

private[sql] object LocatedIndexList {

  def requestedIndicesIn(indexList: String): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    indexList
      .split(',')
      .asSafeList
      .filter(_.nonEmpty)
      .traverse(RequestedIndex.fromString)
      .flatMap(NonEmptyList.fromList)

}

private[sql] final case class ReplacedQuery(query: String, intendedIndices: Set[String]) {

  /** ROR trusts what ES reads back, not its own rewrite: a mismatch means the query is not narrowed. */
  def checkedAgainst(readIndices: Set[String]): Either[Rejection, String] =
    Either.cond(
      test = intendedIndices == readIndices,
      right = query,
      left = Rejection.SubstitutionNotConfirmed(sorted(intendedIndices), sorted(readIndices))
    )

  private def sorted(indices: Set[String]): List[String] = indices.toList.sorted

}
