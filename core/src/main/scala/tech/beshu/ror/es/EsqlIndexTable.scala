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
package tech.beshu.ror.es

import cats.Show
import cats.data.NonEmptyList
import cats.implicits.*
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, IndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import java.util.regex.Pattern

sealed trait EsqlQueryRewriteResult

object EsqlQueryRewriteResult {
  final case class Rewritten(newQuery: String) extends EsqlQueryRewriteResult
  case object CannotRewriteQuery extends EsqlQueryRewriteResult
}

sealed trait EsqlIndexTable {
  def tableStringInQuery: String
  def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
}

object EsqlIndexTable {

  implicit val esqlIndexTableShow: Show[EsqlIndexTable] = Show.show(_.tableStringInQuery)

  final case class From(
      tableStringInQuery: String,
      requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ) extends EsqlIndexTable {
    lazy val matcher: PatternsMatcher[ClusterIndexName] = PatternsMatcher.create(requestedIndices.includedOnly)
  }

  object From {
    def parse(tableStringInQuery: String): Option[From] =
      requestedIndicesFrom(tableStringInQuery).map(From(tableStringInQuery, _))
  }

  final case class LookupJoin(tableStringInQuery: String, index: IndexName.Full) extends EsqlIndexTable {
    val clusterIndexName: ClusterIndexName.Local = ClusterIndexName.Local(index)

    override def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]] =
      NonEmptyList.one(RequestedIndex(clusterIndexName, excluded = false))
  }

  object LookupJoin {

    def parse(tableStringInQuery: String): Option[LookupJoin] =
      ClusterIndexName.fromString(tableStringInQuery).collect { case ClusterIndexName.Local(index: IndexName.Full) =>
        LookupJoin(tableStringInQuery, index)
      }

  }

  final case class Replacement(table: EsqlIndexTable, newIndices: NonEmptyList[ClusterIndexName])

  def requestedIndicesOf(tables: NonEmptyList[EsqlIndexTable]): Set[RequestedIndex[ClusterIndexName]] =
    tables.toList.flatMap(_.requestedIndices.toList).toCovariantSet

  def newQueryFrom(
      oldQuery: String,
      tables: NonEmptyList[EsqlIndexTable],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): EsqlQueryRewriteResult = {
    newQueryFrom(oldQuery, buildReplacements(tables, allowedIndices))
  }

  def buildReplacements(
      tables: NonEmptyList[EsqlIndexTable],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): NonEmptyList[Replacement] = {
    val allowedIndexNames: Set[ClusterIndexName] = allowedIndices.includedOnly

    val lookupOnlyNames: Set[ClusterIndexName] =
      tables.collect { case table: LookupJoin => table.clusterIndexName }.toCovariantSet --
        tables.collect { case table: From => table }.flatMap(_.requestedIndices.includedOnly).toCovariantSet

    def authorizedNamesFor(table: EsqlIndexTable): Option[NonEmptyList[ClusterIndexName]] = table match {
      case table: LookupJoin =>
        Option.when(allowedIndexNames.contains(table.clusterIndexName))(NonEmptyList.one(table.clusterIndexName))
      case table: From =>
        // the first term keeps aliases the ACL resolved to a name the table's pattern cannot match
        NonEmptyList.fromList {
          ((allowedIndexNames -- lookupOnlyNames) ++ table.matcher.filter(allowedIndexNames)).toList
        }
    }

    val nonexistentIndexFor: String => ClusterIndexName = {
      val nonexistentIndexByOriginal =
        tables.toList
          .filter(authorizedNamesFor(_).isEmpty)
          .map(_.tableStringInQuery)
          .distinct
          .map(originalTableText => (originalTableText, ClusterIndexName.Local.randomNonexistentIndex()))
          .toMap
      originalTableText =>
        nonexistentIndexByOriginal.getOrElse(originalTableText, ClusterIndexName.Local.randomNonexistentIndex())
    }

    tables.map { table =>
      val newIndices = authorizedNamesFor(table)
        .getOrElse(NonEmptyList.one(nonexistentIndexFor(table.tableStringInQuery)))
      Replacement(table, newIndices)
    }
  }

  def newQueryFrom(oldQuery: String, replacements: NonEmptyList[Replacement]): EsqlQueryRewriteResult = {
    replacements.toList
      .groupBy(_.table)
      .toList
      .traverse { case (table, tableReplacements) =>
        val spans = indexListSpansIn(oldQuery, table)
        Option.when(spans.size === tableReplacements.size)(spans.zip(tableReplacements))
      }
      .map(_.flatten)
      .flatMap(spliceIndexLists(oldQuery, _))
      .map(EsqlQueryRewriteResult.Rewritten.apply)
      .getOrElse(EsqlQueryRewriteResult.CannotRewriteQuery)
  }

  private def requestedIndicesFrom(tableStringInQuery: String): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    NonEmptyList.fromList(
      tableStringInQuery.split(',').asSafeList.filter(_.nonEmpty).flatMap(RequestedIndex.fromString)
    )

  private def indexListSpansIn(query: String, table: EsqlIndexTable): List[(Int, Int)] = {
    val matcher = indexListPatternOf(table).matcher(query)
    Iterator
      .unfold(())(_ => Option.when(matcher.find())(((matcher.start(1), matcher.end(1)), ())))
      .toList
  }

  private val blank = "(?:\\s|//[^\\n]*|/\\*[\\s\\S]*?\\*/)"

  /** ES reports the list normalized (`FROM a, b` as `a,b`), so instead of searching for it, match every
    * spelling that normalizes to it.
    */
  private def indexListPatternOf(table: EsqlIndexTable): Pattern = {
    val keywords = table match {
      case _: From       => "FROM|TS|METRICS"
      case _: LookupJoin => "LOOKUP\\s+JOIN"
    }
    val optionalQuote = "(?:\"\"\"|\")?"
    val indexList = table.tableStringInQuery
      .split(',')
      .map(index => s"$optionalQuote${Pattern.quote(index)}$optionalQuote")
      .mkString("\\s*,\\s*")
    Pattern.compile(
      s"(?:^|[|(;])$blank*(?:$keywords)$blank+($indexList)(?![\\w.\\-*:])",
      Pattern.CASE_INSENSITIVE
    )
  }

  private def spliceIndexLists(query: String, spans: List[((Int, Int), Replacement)]): Option[String] = {
    val sortedSpans = spans.sortBy { case ((start, _), _) => start }
    val overlapping = sortedSpans.sliding(2).exists {
      case List(((_, previousEnd), _), ((start, _), _)) => previousEnd > start
      case _                                            => false
    }
    if (overlapping) None
    else
      Some {
        sortedSpans.reverse.foldLeft(query) { case (currentQuery, ((start, end), replacement)) =>
          val newIndices = replacement.newIndices.toList.map(_.show).mkString(",")
          s"${currentQuery.substring(0, start)}$newIndices${currentQuery.substring(end)}"
        }
      }
  }

}
