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
import cats.implicits.*
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.esql.RequestClassification.{IndicesRelated, NonIndicesRelated}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.slf4j.Logging

final class EsqlQueryNarrower(reader: EsqlIndexListsReader) extends Logging {

  def classify(query: Query): Either[Rejection, RequestClassification] = {
    for {
      reported <- reader.indexListsIn(query).leftMap { cause =>
        logger.debug("Cannot parse the ES|QL statement", cause)
        Rejection.CannotParseQuery
      }
      located <- IndexListLocator.locatedIn(query, reported).leftMap(Rejection.CannotExtractIndices.apply)
    } yield NonEmptyList.fromList(located) match {
      case Some(indexLists) => new IndicesRelated(query, indexLists)
      case None             => NonIndicesRelated
    }
  }

  def narrowedTo(
      classification: Either[Rejection, RequestClassification],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): Either[Rejection, Option[Query]] = {
    if (allowedIndices.toList.toCovariantSet == EsqlQueryNarrower.requestedIndicesOf(classification)) Right(None)
    else
      classification match {
        case Right(indicesRelated: IndicesRelated) => narrowed(indicesRelated, allowedIndices).map(Some(_))
        case Right(NonIndicesRelated)              => Right(None)
        case Left(rejection)                       => Left(rejection)
      }
  }

  private def narrowed(
      classification: IndicesRelated,
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): Either[Rejection, Query] = {
    val replaced = IndexListReplacer.replacing(classification.query, classification.indexLists, allowedIndices)
    reader.indexListsIn(replaced.query) match {
      case Right(reported) =>
        replaced.checkedAgainst(reported.map(_.read).filterNot(_.indexListIsEmpty))
      case Left(cause) =>
        logger.warn("Elasticsearch cannot parse the ES|QL query ReadonlyREST rewrote", cause)
        Left(Rejection.CannotParseRewrittenQuery(replaced.intendedIndexLists))
    }
  }

}

object EsqlQueryNarrower {

  def requestedIndicesOf(
      classification: Either[Rejection, RequestClassification]
  ): Set[RequestedIndex[ClusterIndexName]] = {
    classification match {
      case Right(indicesRelated: IndicesRelated) =>
        indicesRelated.requestedIndices
      case Right(NonIndicesRelated) | Left(_) =>
        Set(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))
    }
  }

}
