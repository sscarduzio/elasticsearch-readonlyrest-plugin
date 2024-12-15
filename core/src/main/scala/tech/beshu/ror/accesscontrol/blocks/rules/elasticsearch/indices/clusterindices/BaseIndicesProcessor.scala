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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices

import cats.Show
import cats.data.EitherT
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass.No.Reason
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass.No.Reason.IndexNotExist
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.IndicesCheckContinuation.{continue, stop}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.{CanPass, CheckContinuation}
import tech.beshu.ror.accesscontrol.domain.RequestedIndex.*
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, KibanaIndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.{Conversion, Matchable}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

trait BaseIndicesProcessor {
  this: Logging =>

  import BaseIndicesProcessor.*

  protected def canPass[T <: ClusterIndexName : Matchable : Show](requestContext: RequestContext,
                                                                  determinedKibanaIndex: Option[KibanaIndexName],
                                                                  requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                 (implicit indicesManager: IndicesManager[T]): Task[CanPass[Set[RequestedIndex[T]]]] = {
    implicit val requestId: RequestContext.Id = requestContext.id
    if (requestContext.isReadOnlyRequest) canIndicesReadOnlyRequestPass(requestedIndices, determinedKibanaIndex)
    else canIndicesWriteRequestPass(requestedIndices, determinedKibanaIndex)
  }

  private def canIndicesReadOnlyRequestPass[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]],
                                                                                      determinedKibanaIndex: Option[KibanaIndexName])
                                                                                     (implicit requestId: RequestContext.Id,
                                                                                      indicesManager: IndicesManager[T]): Task[CanPass[Set[RequestedIndex[T]]]] = {
    val result = for {
      _ <- EitherT(allKibanaRelatedIndicesMatched(requestedIndices, determinedKibanaIndex))
      _ <- EitherT(noneOrAllIndicesMatched(requestedIndices))
      _ <- EitherT(allIndicesMatchedByWildcard(requestedIndices))
      _ <- EitherT(indicesAliasesDataStreams(requestedIndices))
    } yield ()
    result.value.map(_.left.getOrElse(CanPass.No()))
  }

  private def allKibanaRelatedIndicesMatched[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]],
                                                                                       determinedKibanaIndex: Option[KibanaIndexName])
                                                                                      (implicit requestId: RequestContext.Id): Task[CheckContinuation[Set[RequestedIndex[T]]]] =
    Task.delay {
      determinedKibanaIndex match {
        case Some(kibanaIndexName) =>
          import KibanaIndexName.*
          logger.debug(s"[${requestId.show}] Checking - all requested indices relate to Kibana indices ...")
          val allKibanaRelatedIndices = requestedIndices.forall(_.name.isRelatedToKibanaIndex(kibanaIndexName))
          if (allKibanaRelatedIndices) {
            logger.debug(s"[${requestId.show}] ... matched [indices: ${requestedIndices.show}]. Stop")
            stop(CanPass.Yes(requestedIndices.toCovariantSet))
          } else {
            logger.debug(s"[${requestId.show}] ... not matched. Continue")
            continue[Set[RequestedIndex[T]]]
          }
        case None =>
          continue[Set[RequestedIndex[T]]]
      }
    }

  private def noneOrAllIndicesMatched[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                               (implicit requestId: RequestContext.Id,
                                                                                indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[RequestedIndex[T]]]] = {
    Task.delay {
      logger.debug(s"[${requestId.show}] Checking - none or all indices ...")
    } >>
      indicesManager
        .allIndicesAndAliasesAndDataStreams
        .map { allIndicesAndAliasesAndDataStreams =>
          logger.debug(s"[${requestId.show}] ... indices, aliases and data streams: [${allIndicesAndAliasesAndDataStreams.show}]")
          if (requestedIndices.exists(_.name.allIndicesRequested)) {
            val allowedIndices = indicesManager.allowedIndicesMatcher.filter(allIndicesAndAliasesAndDataStreams)
            stop(
              if (allowedIndices.nonEmpty) {
                logger.debug(s"[${requestId.show}] ... matched [indices: ${requestedIndices.show}]. Stop")
                CanPass.Yes(allowedIndices.map(RequestedIndex(_, excluded = false)))
              } else {
                logger.debug(s"[${requestId.show}] ... not matched. Index not found. Stop")
                CanPass.No(IndexNotExist)
              }
            )
          } else {
            logger.debug(s"[${requestId.show}] ... not matched. Continue")
            continue[Set[RequestedIndex[T]]]
          }
        }
  }

  private def allIndicesMatchedByWildcard[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                   (implicit requestId: RequestContext.Id,
                                                                                    indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[RequestedIndex[T]]]] =
    Task.delay {
      implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
      logger.debug(s"[${requestId.show}] Checking if all indices are matched ...")
      requestedIndices.toList match {
        case requestedIndex :: Nil if !requestedIndex.name.hasWildcard =>
          if (indicesManager.allowedIndicesMatcher.`match`(requestedIndex)(_.name)) {
            logger.debug(s"[${requestId.show}] ... matched [indices: ${requestedIndex.show}]. Stop")
            stop(CanPass.Yes(Set(requestedIndex)))
          } else {
            logger.debug(s"[${requestId.show}] ... not matched. Continue")
            continue
          }
        case _ if requestedIndices.iterator.forall(i => !i.name.hasWildcard) &&
          indicesManager.allowedIndicesMatcher.filter(requestedIndices) == requestedIndices.toCovariantSet =>
          logger.debug(s"[${requestId.show}] ... matched [indices: ${requestedIndices.show}]. Stop")
          stop(CanPass.Yes(requestedIndices.toCovariantSet))
        case _ =>
          logger.debug(s"[${requestId.show}] ... not matched. Continue")
          continue[Set[RequestedIndex[T]]]
      }
    }

  private def indicesAliasesDataStreams[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                 (implicit requestId: RequestContext.Id,
                                                                                  indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[RequestedIndex[T]]]] = {
    Task
      .delay {
        logger.debug(s"[${requestId.show}] Checking - indices & aliases & data streams...")
      } >> Task
      .sequence(
        // indices requested
        filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(requestedIndices) ::
          filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured[T]() ::
          filterAssumingThatIndicesAreRequestedAndDataStreamsAreConfigured(requestedIndices) ::
          filterAssumingThatIndicesAreRequestedAndDataStreamAliasesAreConfigured[T]() ::
          // aliases requested
          filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured(requestedIndices) ::
          filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured(requestedIndices) ::
          filterAssumingThatAliasesAreRequestedAndDataStreamsAreConfigured(requestedIndices) ::
          filterAssumingThatAliasesAreRequestedAndDataStreamAliasesAreConfigured(requestedIndices) ::
          // data streams requested
          filterAssumingThatDataStreamsAreRequestedAndIndicesAreConfigured(requestedIndices) ::
          filterAssumingThatDataStreamsAreRequestedAndAliasesAreConfigured[T]() ::
          filterAssumingThatDataStreamsAreRequestedAndDataStreamsAreConfigured(requestedIndices) ::
          filterAssumingThatDataStreamsAreRequestedAndDataStreamAliasesAreConfigured[T]() :: Nil
      )
      .map(_.flatten.toCovariantSet)
      .map { allowedRealIndices =>
        if (allowedRealIndices.nonEmpty) {
          logger.debug(s"[${requestId.show}] ... matched [indices: ${allowedRealIndices.show}]. Stop")
          stop(CanPass.Yes(allowedRealIndices))
        } else {
          logger.debug(s"[${requestId.show}] ... not matched. Stop!")
          stop(CanPass.No(Reason.IndexNotExist))
        }
      }
  }

  private def filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                                                    (implicit indicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    indicesManager
      .allIndices
      .map { allIndices =>
        val resolvedRequestedIndices = allIndices.filterBy(requestedIndices)
        implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
        indicesManager.allowedIndicesMatcher.filter(resolvedRequestedIndices)
      }
  }

  private def filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName](): Task[Set[RequestedIndex[T]]] = {
    // eg. alias A1 of index I1 can be defined with filtering, so result of /I1/_search will be different than
    // result of /A1/_search. It means that if indices are requested and aliases are configured, the result of
    // this kind of method will always be an empty set.
    Task.now(Set.empty[RequestedIndex[T]])
  }

  private def filterAssumingThatIndicesAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]]
                                                                                                                        )(implicit indicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    indicesManager
      .backingIndicesPerDataStreamMap
      .map { backingIndicesPerDataStream =>
        val requestedBackingIndicesMatcher = PatternsMatcher.create(requestedIndices.map(_.name))

        val requestedBackingIndicesPerDataStream: Map[T, UniqueNonEmptyList[RequestedIndex[T]]] =
          backingIndicesPerDataStream
            .flatMap {
              case (dataStreamName, backingIndices) =>
                // we skip the backing indices for a given data stream when the data stream name matches the indices pattern too
                // (for indices pattern like (e.g. `*logs*`) the query would be exploded to logs_ds, .ds-logs_ds_0001, .ds-logs_ds_0002, etc.
                // passing the data stream name for such case is enough
                if (!requestedBackingIndicesMatcher.`match`(dataStreamName)) {
                  UniqueNonEmptyList
                    .from(backingIndices.filterBy(requestedIndices))
                    .map(indices => (dataStreamName, indices))
                } else {
                  Option.empty[(T, UniqueNonEmptyList[RequestedIndex[T]])]
                }
            }
        val allowedMatcher = indicesManager.allowedIndicesMatcher
        val backingIndicesMatchedByAllowedDataStreamName =
          requestedBackingIndicesPerDataStream
            .filter {
              case (dataStreamName, backingIndices) => allowedMatcher.`match`(dataStreamName)
            }
            .values
            .flatten
            .toCovariantSet

        implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
        val backingIndicesMatchedByAllowedBackingIndices =
          requestedBackingIndicesPerDataStream
            .flatMap {
              case (dataStreamName, backingIndices) => allowedMatcher.filter(backingIndices)
            }
            .toCovariantSet
        backingIndicesMatchedByAllowedDataStreamName ++ backingIndicesMatchedByAllowedBackingIndices
      }
  }

  private def filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                                                    (implicit indicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    indicesManager
      .allAliases
      .map { allAliases =>
        val requestedAliasesNames = requestedIndices
        val requestedAliases = allAliases.filterBy(requestedAliasesNames)
        implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
        indicesManager.allowedIndicesMatcher.filter(requestedAliases)
      }
  }

  private def filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                                                    (implicit indicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    for {
      allAliases <- indicesManager.allAliases
      aliasesPerIndex <- indicesManager.indicesPerAliasMap
    } yield {
      val requestedAliasesNames = requestedIndices
      val requestedAliases = allAliases.filterBy(requestedAliasesNames)

      val indicesOfRequestedAliases: Set[RequestedIndex[T]] =
        requestedAliases.flatMap { requestedAlias =>
          aliasesPerIndex
            .getOrElse(requestedAlias.name, Set.empty)
            .map { alias => RequestedIndex(alias, requestedAlias.excluded) }
        }

      implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
      indicesManager
        .allowedIndicesMatcher
        .filter(indicesOfRequestedAliases)
    }
  }

  private def filterAssumingThatAliasesAreRequestedAndDataStreamAliasesAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                                                              (implicit indicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    indicesManager
      .allDataStreamAliases
      .map { allDataStreamAliases =>
        val requestedAliasesNames = requestedIndices
        val requestedAliases = allDataStreamAliases.filterBy(requestedAliasesNames)
        implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
        indicesManager.allowedIndicesMatcher.filter(requestedAliases)
      }
  }

  private def filterAssumingThatDataStreamsAreRequestedAndDataStreamAliasesAreConfigured[T <: ClusterIndexName](): Task[Set[RequestedIndex[T]]] = {
    // eg. alias A1 of data stream DS1 can be defined with filtering, so result of /DS1/_search will be different than
    // result of /A1/_search. It means that if data streams are requested and data stream aliases are configured, the result of
    // this kind of method will always be an empty set.
    Task.now(Set.empty[RequestedIndex[T]])
  }

  private def filterAssumingThatIndicesAreRequestedAndDataStreamAliasesAreConfigured[T <: ClusterIndexName](): Task[Set[RequestedIndex[T]]] = {
    // eg. alias A1 of data stream DS1 with backing index I1 can be defined with filtering, so result of /I1/_search will be different than
    // result of /A1/_search. It means that if backing indices are requested and data stream aliases are configured, the result of
    // this kind of method will always be an empty set.
    Task.now(Set.empty[RequestedIndex[T]])
  }

  private def filterAssumingThatAliasesAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                                                        (implicit indicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    for {
      allAliases <- indicesManager.allDataStreamAliases
      aliasesPerDataStream <- indicesManager.dataStreamsPerAliasMap
    } yield {
      val requestedAliasesNames = requestedIndices
      val requestedAliases = allAliases.filterBy(requestedAliasesNames)
      val dataStreamsOfRequestedAliases = requestedAliases.flatMap(requestedAlias =>
        aliasesPerDataStream
          .getOrElse(requestedAlias.name, Set.empty)
          .map(alias => RequestedIndex(alias, requestedAlias.excluded))
      )
      implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
      indicesManager.allowedIndicesMatcher.filter(dataStreamsOfRequestedAliases)
    }
  }

  private def filterAssumingThatDataStreamsAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                                                        (implicit indicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    for {
      allDataStreams <- indicesManager.allDataStreams
      backingIndicesPerDataStream <- indicesManager.backingIndicesPerDataStreamMap
    } yield {
      val requestedDataStreamsNames = requestedIndices
      val requestedDataStreams = allDataStreams.filterBy(requestedDataStreamsNames)
      val allowedDataStreamsMatcher = indicesManager.allowedIndicesMatcher

      val indicesOfRequestedDataStream = requestedDataStreams
        .flatMap { requestedDataStreamName =>
          if (!allowedDataStreamsMatcher.`match`(requestedDataStreamName.name)) {
            backingIndicesPerDataStream
              .getOrElse(requestedDataStreamName.name, Set.empty)
              .map(backingIndex => RequestedIndex(backingIndex, requestedDataStreamName.excluded))
          } else {
            Set.empty[RequestedIndex[T]]
          }
        }

      implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name.asInstanceOf[T])
      indicesManager.allowedIndicesMatcher.filter(indicesOfRequestedDataStream)
    }
  }

  private def filterAssumingThatDataStreamsAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName](): Task[Set[RequestedIndex[T]]] = {
    // eg. alias A1 of data stream DS1 can be defined with filtering, so result of /DS1/_search will be different than
    // result of /A1/_search. It means that if data streams are requested and aliases are configured, the result of
    // this kind of method will always be an empty set.
    Task.now(Set.empty[RequestedIndex[T]])
  }

  private def filterAssumingThatDataStreamsAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                                                            (implicit indicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    indicesManager
      .allDataStreams
      .map { allDataStreams =>
        val requestedDataStreamsNames = requestedIndices
        val requestedDataStreams = allDataStreams.filterBy(requestedDataStreamsNames)
        implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
        indicesManager.allowedIndicesMatcher.filter(requestedDataStreams)
      }
  }

  private def canIndicesWriteRequestPass[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]],
                                                                                   determinedKibanaIndex: Option[KibanaIndexName])
                                                                                  (implicit requestId: RequestContext.Id,
                                                                                   indicesManager: IndicesManager[T]): Task[CanPass[Set[RequestedIndex[T]]]] = {
    val result = for {
      _ <- EitherT(allKibanaRelatedIndicesMatched(requestedIndices, determinedKibanaIndex))
      _ <- EitherT(generalWriteRequest(requestedIndices))
    } yield ()
    result.value.map(_.left.getOrElse(CanPass.No()))
  }

  private def generalWriteRequest[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                           (implicit requestId: RequestContext.Id,
                                                                            indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[RequestedIndex[T]]]] = {
    Task.delay {
      logger.debug(s"[${requestId.show}] Checking - write request ...")
      // Write requests
      logger.debug(s"[${requestId.show}] Stage 7")
      if (requestedIndices.isEmpty && indicesManager.allowedIndicesMatcher.contains("<no-index>")) {
        logger.debug(s"[${requestId.show}] ... matched [indices: ${requestedIndices.show}]. Stop")
        stop(CanPass.Yes(requestedIndices.toCovariantSet))
      } else {
        // Reject write if at least one requested index is not allowed by the rule conf
        logger.debug(s"[${requestId.show}] Stage 8")
        stop {
          requestedIndices.find(requestedIndex => !indicesManager.allowedIndicesMatcher.`match`(requestedIndex.name)) match {
            case Some(_) =>
              logger.debug(s"[${requestId.show}] ... not matched. Stop")
              CanPass.No()
            case None =>
              logger.debug(s"[${requestId.show}] ... matched [indices: ${requestedIndices.show}]. Stop")
              CanPass.Yes(requestedIndices.toCovariantSet)
          }
        }
      }
    }
  }

}

object BaseIndicesProcessor {

  trait IndicesManager[T <: ClusterIndexName] {
    final def allIndicesAndAliasesAndDataStreams: Task[Set[T]] = {
      for {
        indicesAndAliases <- allIndicesAndAliases
        dataStreamsAndAliases <- allDataStreamsAndDataStreamAliases
      } yield indicesAndAliases ++ dataStreamsAndAliases
    }

    // indices and aliases
    def allIndicesAndAliases: Task[Set[T]]

    def allIndices: Task[Set[T]]

    def allAliases: Task[Set[T]]

    def indicesPerAliasMap: Task[Map[T, Set[T]]]

    // data streams and their aliases
    def allDataStreamsAndDataStreamAliases: Task[Set[T]]

    def allDataStreams: Task[Set[T]]

    def allDataStreamAliases: Task[Set[T]]

    def dataStreamsPerAliasMap: Task[Map[T, Set[T]]]

    def backingIndicesPerDataStreamMap: Task[Map[T, Set[T]]]

    def allowedIndicesMatcher: PatternsMatcher[T]
  }

  private implicit class IndicesFilteredBy[T <: ClusterIndexName](indices: Set[T]) extends AnyVal {

    def filterBy(requestedIndices: Iterable[RequestedIndex[T]]): Set[RequestedIndex[T]] = {
      val (excluded, included) = requestedIndices.toSet.partition(_.excluded)
      val excludedRequestedIndices = if (excluded.nonEmpty) {
        PatternsMatcher
          .create(excluded.map(_.name))
          .filter(indices)
          .map(RequestedIndex(_, excluded = true))
      } else {
        Set.empty[RequestedIndex[T]]
      }
      val excludedIndicesNames = excludedRequestedIndices.map(_.name)
      val includedRequestedIndices = if (included.nonEmpty) {
        PatternsMatcher
          .create(included.map(_.name))
          .filter(indices)
          .filterNot(index => excludedIndicesNames.contains(index))
          .map(RequestedIndex(_, excluded = false))
      } else {
        Set.empty[RequestedIndex[T]]
      }
      if(includedRequestedIndices.exists(_.name.hasWildcard)) {
        includedRequestedIndices ++ excludedRequestedIndices
      } else {
        includedRequestedIndices
      }
    }
  }
}