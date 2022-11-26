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
package tech.beshu.ror.es.handler.request.context.types.datastreams

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.ActionRequest
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.handler.request.context.types.datastreams.ReflectionBasedDataStreamsEsRequestContext.MatchResult.{Matched, NotMatched}
import tech.beshu.ror.es.handler.request.context.types.{BaseIndicesEsRequestContext, ReflectionBasedActionRequest}
import tech.beshu.ror.utils.ReflecUtils.extractStringArrayFromPrivateMethod
import tech.beshu.ror.utils.ScalaOps._

object ReflectionBasedDataStreamsEsRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[BaseIndicesEsRequestContext[ActionRequest]] = {
    CreateDataStreamEsRequestContext.unapply(arg)
      .orElse(DataStreamsStatsEsRequestContext.unapply(arg))
      .orElse(DeleteDataStreamEsRequestContext.unapply(arg))
      .orElse(GetDataStreamEsRequestContext.unapply(arg))
      .orElse(MigrateToDataStreamEsRequestContext.unapply(arg))
      .orElse(PromoteDataStreamEsRequestContext.unapply(arg))
  }

  private[datastreams] def tryMatchActionRequest(actionRequest: ActionRequest,
                                                 expectedClassCanonicalName: String,
                                                 getIndicesMethodName: String): MatchResult = {
    Option(actionRequest.getClass.getCanonicalName)
      .find(_ == expectedClassCanonicalName)
      .flatMap { _ =>
        NonEmptyList
          .fromList(extractStringArrayFromPrivateMethod(getIndicesMethodName, actionRequest).asSafeList)
          .map(_.toList.toSet.flatMap(ClusterIndexName.fromString))
          .map(Matched.apply)
      }
      .getOrElse(NotMatched)
  }

  private[datastreams] sealed trait MatchResult
  private[datastreams] object MatchResult {
    final case class Matched(extractedIndices: Set[ClusterIndexName]) extends MatchResult
    object NotMatched extends MatchResult
  }

}