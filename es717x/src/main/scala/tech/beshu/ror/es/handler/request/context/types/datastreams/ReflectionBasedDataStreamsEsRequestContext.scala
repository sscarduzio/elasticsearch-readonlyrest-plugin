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

import cats.implicits.*
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.common.util.set.Sets
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, DataStreamName, RequestedIndex}
import tech.beshu.ror.es.handler.request.context.types.datastreams.ReflectionBasedDataStreamsEsRequestContext.MatchResult.{Matched, NotMatched}
import tech.beshu.ror.es.handler.request.context.types.{BaseDataStreamsEsRequestContext, ReflectionBasedActionRequest}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ReflecUtils
import tech.beshu.ror.utils.ReflecUtils.extractStringArrayFromPrivateMethod
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.jdk.CollectionConverters.*

object ReflectionBasedDataStreamsEsRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[BaseDataStreamsEsRequestContext[ActionRequest]] = {
    esContextCreators
      .to(LazyList)
      .flatMap(_.unapply(arg))
      .headOption
  }

  val supportedActionRequests: Set[ClassCanonicalName] = esContextCreators.map(_.actionRequestClass).toCovariantSet

  private lazy val esContextCreators: UniqueNonEmptyList[ReflectionBasedDataStreamsEsContextCreator] = UniqueNonEmptyList.of(
    CreateDataStreamEsRequestContext,
    DataStreamsStatsEsRequestContext,
    DeleteDataStreamEsRequestContext,
    GetDataStreamEsRequestContext,
    MigrateToDataStreamEsRequestContext,
    PromoteDataStreamEsRequestContext
  )

  private[datastreams] def tryUpdateDataStreams[R <: ActionRequest](actionRequest: R,
                                                                    dataStreamsFieldName: String,
                                                                    dataStreams: Set[DataStreamName]): Boolean = {
    // Optimistic reflection attempt
    ReflecUtils.setIndices(
      actionRequest,
      Sets.newHashSet(dataStreamsFieldName),
      dataStreams.map(DataStreamName.toString).toSet.asJava
    )
  }


  private[datastreams] sealed trait MatchResult[A]

  private[datastreams] object MatchResult {
    final case class Matched[A](extracted: Set[A]) extends MatchResult[A]

    final case class NotMatched[A]() extends MatchResult[A]
  }

  final case class ClassCanonicalName(value: String) extends AnyVal

  private[datastreams] trait ReflectionBasedDataStreamsEsContextCreator {

    def actionRequestClass: ClassCanonicalName

    def unapply(arg: ReflectionBasedActionRequest): Option[BaseDataStreamsEsRequestContext[ActionRequest]]

    protected def tryMatchActionRequestWithIndices(actionRequest: ActionRequest,
                                                   getIndicesMethodName: String): MatchResult[RequestedIndex[ClusterIndexName]] = {
      tryMatchActionRequest[RequestedIndex[ClusterIndexName]](
        actionRequest = actionRequest,
        getPropsMethodName = getIndicesMethodName,
        toDomain = RequestedIndex.fromString
      )
    }

    protected def tryMatchActionRequestWithDataStreams(actionRequest: ActionRequest,
                                                       getDataStreamsMethodName: String): MatchResult[DataStreamName] = {
      tryMatchActionRequest[DataStreamName](
        actionRequest = actionRequest,
        getPropsMethodName = getDataStreamsMethodName,
        toDomain = DataStreamName.fromString
      )
    }

    private def tryMatchActionRequest[A](actionRequest: ActionRequest,
                                         getPropsMethodName: String,
                                         toDomain: String => Option[A]): MatchResult[A] = {
      Option(actionRequest.getClass.getCanonicalName)
        .find(_ === actionRequestClass.value)
        .map { _ =>
          Matched.apply[A] {
            extractStringArrayFromPrivateMethod(getPropsMethodName, actionRequest)
              .asSafeSet
              .flatMap((value: String) => toDomain(value))
          }
        }
        .getOrElse(NotMatched())
    }

  }

}