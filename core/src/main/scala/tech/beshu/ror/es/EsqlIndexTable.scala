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

  // LOOKUP JOIN's target must be a single, specific index name or alias: no wildcards, no comma lists
  // https://www.elastic.co/docs/reference/query-languages/esql/commands/lookup-join
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

    val (lookupTables, fromTables) = tables.toList.partitionMap {
      case table: LookupJoin => Left(table)
      case table: From       => Right(table)
    }

    val (nonexistentIndexByOriginal, lookupReplacements) =
      buildLookupReplacements(lookupTables, allowedIndexNames)

    // Excludes names a FROM table also asks for, so a shared literal isn't stripped from FROM's replacement.
    val lookupOnlyNames: Set[ClusterIndexName] =
      lookupTables.map(_.clusterIndexName).toCovariantSet -- fromTables
        .flatMap(_.requestedIndices.includedOnly)
        .toCovariantSet

    val fromReplacements =
      buildFromReplacements(fromTables, allowedIndexNames, lookupOnlyNames, nonexistentIndexByOriginal)

    NonEmptyList.fromListUnsafe(fromReplacements ++ lookupReplacements)
  }

  def newQueryFrom(oldQuery: String, replacements: NonEmptyList[Replacement]): EsqlQueryRewriteResult = {
    replacements.toList
      .traverse(replacement => indexListSpanIn(oldQuery, replacement.table).map((_, replacement)))
      .flatMap(spliceIndexLists(oldQuery, _))
      .map(EsqlQueryRewriteResult.Rewritten.apply)
      .getOrElse(EsqlQueryRewriteResult.CannotRewriteQuery)
  }

  private def requestedIndicesFrom(tableStringInQuery: String): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    NonEmptyList.fromList(
      tableStringInQuery.split(',').asSafeList.filter(_.nonEmpty).flatMap(RequestedIndex.fromString)
    )

  // The returned map keeps a literal masked in both a FROM and a LOOKUP JOIN masking to the same index.
  private def buildLookupReplacements(
      tables: List[LookupJoin],
      allowedIndices: Set[ClusterIndexName]
  ): (Map[String, ClusterIndexName], List[Replacement]) = {
    val (nonexistentIndexByOriginal, reversedReplacements) =
      tables.foldLeft((Map.empty[String, ClusterIndexName], List.empty[Replacement])) {
        case ((nonexistentIndexByOriginal, replacements), table) =>
          if (allowedIndices.contains(table.clusterIndexName)) {
            (nonexistentIndexByOriginal, Replacement(table, NonEmptyList.one(table.clusterIndexName)) :: replacements)
          } else {
            val (updatedMap, nonexistentIndex) =
              nonexistentIndexFor(nonexistentIndexByOriginal, table.tableStringInQuery)
            (updatedMap, Replacement(table, NonEmptyList.one(nonexistentIndex)) :: replacements)
          }
      }
    (nonexistentIndexByOriginal, reversedReplacements.reverse)
  }

  // `allowedIndices -- lookupOnlyNames` recovers ACL-resolved alias names that don't textually match
  // the query (e.g. "bookshop" resolved to "bookstore"); the PatternsMatcher term on top still attributes
  // wildcard matches (e.g. book_* matching book_prices) that lookupOnlyNames alone would exclude.
  private def buildFromReplacements(
      tables: List[From],
      allowedIndices: Set[ClusterIndexName],
      lookupOnlyNames: Set[ClusterIndexName],
      initialNonexistentIndexByOriginal: Map[String, ClusterIndexName]
  ): List[Replacement] = {
    val (_, reversedReplacements) =
      tables.foldLeft((initialNonexistentIndexByOriginal, List.empty[Replacement])) {
        case ((nonexistentIndexByOriginal, replacements), table) =>
          val candidateNames = (allowedIndices -- lookupOnlyNames) ++ table.matcher.filter(allowedIndices)
          NonEmptyList.fromList(candidateNames.toList) match {
            case Some(newIndices) =>
              (nonexistentIndexByOriginal, Replacement(table, newIndices) :: replacements)
            case None =>
              val (updatedMap, nonexistentIndex) =
                nonexistentIndexFor(nonexistentIndexByOriginal, table.tableStringInQuery)
              (updatedMap, Replacement(table, NonEmptyList.one(nonexistentIndex)) :: replacements)
          }
      }
    reversedReplacements.reverse
  }

  private def nonexistentIndexFor(
      nonexistentIndexByOriginal: Map[String, ClusterIndexName],
      originalTableText: String
  ): (Map[String, ClusterIndexName], ClusterIndexName) = {
    nonexistentIndexByOriginal.get(originalTableText) match {
      case Some(existing) => (nonexistentIndexByOriginal, existing)
      case None           =>
        val generated = ClusterIndexName.Local.randomNonexistentIndex()
        (nonexistentIndexByOriginal.updated(originalTableText, generated), generated)
    }
  }

  /** `None` unless the list can be located exactly once - an ambiguous query is rejected, not guessed at. */
  private def indexListSpanIn(query: String, table: EsqlIndexTable): Option[(Int, Int)] = {
    val matcher = indexListPatternOf(table).matcher(query)
    Option.when(matcher.find())((matcher.start(1), matcher.end(1))).filterNot(_ => matcher.find())
  }

  /** ES reports the list normalized - entries joined with `,`, quoting stripped - so `FROM a, b` is reported
    * as `a,b` and text search for it finds nothing. Matching every spelling that normalizes to it, anchored
    * to the keyword, also keeps identifiers that merely share an index's name out of the rewrite.
    */
  private def indexListPatternOf(table: EsqlIndexTable): Pattern = {
    val keywords = table match {
      case _: From       => "FROM|TS"
      case _: LookupJoin => "LOOKUP\\s+JOIN"
    }
    val optionalQuote = "(?:\"\"\"|\")?"
    val indexList = table.tableStringInQuery
      .split(',')
      .map(index => s"$optionalQuote${Pattern.quote(index)}$optionalQuote")
      .mkString("\\s*,\\s*")
    Pattern.compile(
      s"(?:^|[|(])\\s*(?:$keywords)\\s+($indexList)(?![\\w.\\-*:])",
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
