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
package tech.beshu.ror.es.query.esql

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.es.query.Query.WithIndices
import tech.beshu.ror.es.query.QueryIndicesReader.ReadError
import tech.beshu.ror.es.query.esql.EsqlQuery.Rejection
import tech.beshu.ror.es.query.{Query, QueryNarrowing}
import tech.beshu.ror.utils.RequestIdAwareLogging

object EsqlQueryNarrowing extends QueryNarrowing[LocatedIndexList, Rejection] with RequestIdAwareLogging {

  override protected def rewritten(
      query: WithIndices[LocatedIndexList],
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  )(
      implicit requestId: RequestId
  ): Either[Rejection, EsqlQuery] = {
    val replaced = IndexListReplacer.replacing(query.text, query.indexLists, filteredRequestedIndices)
    query.reader.indicesIn(replaced.query) match {
      case Right(readIndexLists) =>
        replaced.checkedAgainst(readIndexLists) match {
          case Right(narrowed) =>
            Right(Query.readable(narrowed, readIndexLists, query.reader))
          case Left(rejection) =>
            logger.debug(s"The ES|QL query [${query.text}] was rewritten to [${replaced.query}]")
            Left(rejection)
        }
      case Left(ReadError.QueryNotParsed(cause)) =>
        logger.warn("Elasticsearch cannot parse the ES|QL query ReadonlyREST rewrote", cause)
        Left(Rejection.CannotParseRewrittenQuery(replaced.intendedIndexLists))
      case Left(ReadError.PlanNotRead(cause)) =>
        logger.warn("ReadonlyREST cannot read the plan Elasticsearch built for the rewritten ES|QL query", cause)
        Left(Rejection.CannotReadQuery)
      case Left(ReadError.IndicesNotLocated(failure)) =>
        Left(Rejection.RewriteNotConfirmed(replaced.intendedIndexLists, failure))
    }
  }

}
