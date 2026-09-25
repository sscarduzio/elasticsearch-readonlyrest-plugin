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
import enumeratum.{Enum, EnumEntry}
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.query.TextSpan
import tech.beshu.ror.syntax.*

private[sql] sealed trait CommandSelector

private[sql] object CommandSelector {

  case object NotIndexRelated extends CommandSelector

  case object AppendableIndexList extends CommandSelector

  final case class LiteralIndexList(indexList: String) extends CommandSelector

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
) {

  def indexNames: Set[String] =
    requestedIndices.toList
      .map(index => if (index.excluded) s"-${index.name.stringify}" else index.name.stringify)
      .toCovariantSet

}
