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
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import java.util.regex.{Matcher, Pattern}

sealed trait EsqlIndexTable {
  def tableStringInQuery: String
  def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
}

object EsqlIndexTable {

  // FROM supports wildcards, comma-separated lists, and `cluster:index` remote references.
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

  // LOOKUP JOIN's target must be a single, specific index name or alias: no wildcards, no comma
  // lists, no remote cluster prefix (https://www.elastic.co/docs/reference/query-languages/esql/commands/lookup-join).
  final case class LookupJoin(tableStringInQuery: String, index: ClusterIndexName) extends EsqlIndexTable {
    override def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]] =
      NonEmptyList.one(RequestedIndex(index, excluded = false))
  }

  object LookupJoin {
    def parse(tableStringInQuery: String): Option[LookupJoin] =
      requestedIndicesFrom(tableStringInQuery).map(indices => LookupJoin(tableStringInQuery, indices.head.name))
  }

  final case class Replacement(table: EsqlIndexTable, newIndices: NonEmptyList[ClusterIndexName])

  def requestedIndicesOf(tables: NonEmptyList[EsqlIndexTable]): Set[RequestedIndex[ClusterIndexName]] =
    tables.toList.flatMap(_.requestedIndices.toList).toCovariantSet

  private def requestedIndicesFrom(tableStringInQuery: String): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    NonEmptyList.fromList(
      tableStringInQuery.split(',').asSafeList.filter(_.nonEmpty).flatMap(RequestedIndex.fromString)
    )

  def newQueryFrom(
      oldQuery: String,
      tables: NonEmptyList[EsqlIndexTable],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): String = {
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
      lookupTables.map(_.index).toCovariantSet -- fromTables.flatMap(_.requestedIndices.includedOnly).toCovariantSet

    val fromReplacements =
      buildFromReplacements(fromTables, allowedIndexNames, lookupOnlyNames, nonexistentIndexByOriginal)

    NonEmptyList.fromListUnsafe(fromReplacements ++ lookupReplacements)
  }

  def newQueryFrom(oldQuery: String, replacements: NonEmptyList[Replacement]): String = {
    replacements.toList.foldLeft(oldQuery) { case (currentQuery, replacement) =>
      val (beforeFrom, afterFrom) = currentQuery.splitBy("FROM")
      afterFrom match {
        case None =>
          replaceTableNameInQueryPart(currentQuery, replacement.table.tableStringInQuery, replacement.newIndices)
        case Some(tablesPart) =>
          s"${beforeFrom}FROM ${replaceTableNameInQueryPart(tablesPart, replacement.table.tableStringInQuery, replacement.newIndices)}"
      }
    }
  }

  // The returned map is threaded into `buildFromReplacements`: if the same literal text is masked in
  // both a FROM and a LOOKUP JOIN, `newQueryFrom`'s single per-table substitution needs both to
  // resolve to the same nonexistent name.
  private def buildLookupReplacements(
      tables: List[LookupJoin],
      allowedIndices: Set[ClusterIndexName]
  ): (Map[String, ClusterIndexName], List[Replacement]) = {
    val (nonexistentIndexByOriginal, reversedReplacements) =
      tables.foldLeft((Map.empty[String, ClusterIndexName], List.empty[Replacement])) {
        case ((nonexistentIndexByOriginal, replacements), table) =>
          if (allowedIndices.contains(table.index)) {
            (nonexistentIndexByOriginal, Replacement(table, NonEmptyList.one(table.index)) :: replacements)
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

  // Anchored to non-identifier boundaries so `originTable` doesn't match as a substring of a longer
  // identifier (e.g. "book" inside "book_prices").
  private def replaceTableNameInQueryPart(
      currentQuery: String,
      originTable: String,
      newIndices: NonEmptyList[ClusterIndexName]
  ): String = {
    val nonIdentifierChar = "[^\\w.\\-*:]"
    val pattern = s"(^|$nonIdentifierChar)${Pattern.quote(originTable)}(?=$nonIdentifierChar|$$)"
    val replacement = "$1" + Matcher.quoteReplacement(newIndices.toList.map(_.show).mkString(","))
    currentQuery.replaceAll(pattern, replacement)
  }

}
