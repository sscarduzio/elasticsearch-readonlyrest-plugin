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
package tech.beshu.ror.es.query

import tech.beshu.ror.es.query.QueryIndicesReader.ReadError

trait QueryIndicesReader[+LOCATED <: LocatedIndexList] {

  private[query] def indicesIn(query: String): Either[ReadError, List[LOCATED]]

}

object QueryIndicesReader {

  sealed trait ReadError

  object ReadError {

    final case class QueryNotParsed(cause: Throwable) extends ReadError

    final case class PlanNotRead(cause: Throwable) extends ReadError

    final case class IndicesNotLocated(failure: ReadingFailure) extends ReadError

  }

  sealed trait ReadingFailure

  object ReadingFailure {

    final case class NotWhereEsReportedIt(indexList: String) extends ReadingFailure

    final case class UnsupportedIndexList(indexList: String) extends ReadingFailure

    final case class OverlappingIndexLists(oneWrittenAs: String, otherWrittenAs: String) extends ReadingFailure

    final case class SubqueryInSourceCommand(indexList: String) extends ReadingFailure

    case object IndexListInAnonymousParameter extends ReadingFailure

    final case class IndexListNotWrittenOnce(indexList: String) extends ReadingFailure

    final case class PatternNotWrittenOnce(commandName: String, indexNameWildcard: String) extends ReadingFailure

    final case class CommandTakesNoIndexList(commandName: String) extends ReadingFailure

  }

}
