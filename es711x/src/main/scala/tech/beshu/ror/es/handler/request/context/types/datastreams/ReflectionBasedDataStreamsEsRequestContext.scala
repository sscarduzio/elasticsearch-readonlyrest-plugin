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

import cats.implicits._
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.common.util.set.Sets
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, DataStreamName}
import tech.beshu.ror.es.handler.request.context.types.datastreams.ReflectionBasedDataStreamsEsRequestContext.MatchResult.{Matched, NotMatched}
import tech.beshu.ror.es.handler.request.context.types.{BaseDataStreamsEsRequestContext, ReflectionBasedActionRequest}
import tech.beshu.ror.utils.ReflecUtils
import tech.beshu.ror.utils.ReflecUtils.extractStringArrayFromPrivateMethod
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

object ReflectionBasedDataStreamsEsRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[BaseDataStreamsEsRequestContext[ActionRequest]] = {
    CreateDataStreamEsRequestContext.unapply(arg)
      .orElse(DataStreamsStatsEsRequestContext.unapply(arg))
      .orElse(DeleteDataStreamEsRequestContext.unapply(arg))
      .orElse(GetDataStreamEsRequestContext.unapply(arg))
      .orElse(MigrateToDataStreamEsRequestContext.unapply(arg))
      .orElse(PromoteDataStreamEsRequestContext.unapply(arg))
  }

  private[datastreams] def tryMatchActionRequestWithIndices(actionRequest: ActionRequest,
                                                            expectedClassCanonicalName: String,
                                                            getIndicesMethodName: String): MatchResult[ClusterIndexName] = {
    tryMatchActionRequest[ClusterIndexName](
      actionRequest = actionRequest,
      expectedClassCanonicalName = expectedClassCanonicalName,
      getPropsMethodName = getIndicesMethodName,
      toDomain = ClusterIndexName.fromString
    )
  }

  private[datastreams] def tryMatchActionRequestWithDataStreams(actionRequest: ActionRequest,
                                                                expectedClassCanonicalName: String,
                                                                getDataStreamsMethodName: String): MatchResult[DataStreamName] = {
    tryMatchActionRequest[DataStreamName](
      actionRequest = actionRequest,
      expectedClassCanonicalName = expectedClassCanonicalName,
      getPropsMethodName = getDataStreamsMethodName,
      toDomain = DataStreamName.fromString
    )
  }

  private def tryMatchActionRequest[A](actionRequest: ActionRequest,
                                       expectedClassCanonicalName: String,
                                       getPropsMethodName: String,
                                       toDomain: String => Option[A]): MatchResult[A] = {
    Option(actionRequest.getClass.getCanonicalName)
      .find(_ == expectedClassCanonicalName)
      .map { _ =>
        Matched.apply[A] {
          extractStringArrayFromPrivateMethod(getPropsMethodName, actionRequest)
            .asSafeList
            .toSet
            .flatMap((value: String) => toDomain(value))
        }
      }
      .getOrElse(NotMatched)
  }

  private[datastreams] def tryUpdateDataStreams[R <: ActionRequest](actionRequest: R,
                                                                    dataStreamsFieldName: String,
                                                                    dataStreams: Set[DataStreamName]): Boolean = {
    // Optimistic reflection attempt
    ReflecUtils.setIndices(
      actionRequest,
      Sets.newHashSet(dataStreamsFieldName),
      dataStreams.toList.map(DataStreamName.toString).toSet.asJava
    )
  }


  private[datastreams] sealed trait MatchResult[+A]

  private[datastreams] object MatchResult {
    final case class Matched[A](extracted: Set[A]) extends MatchResult[A]

    object NotMatched extends MatchResult[Nothing]
  }

}