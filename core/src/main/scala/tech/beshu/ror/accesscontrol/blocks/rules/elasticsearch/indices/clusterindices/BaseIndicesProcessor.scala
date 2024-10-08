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

import cats.data.EitherT
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices.BaseIndicesProcessor.IndicesManager
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass.No.Reason
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass.No.Reason.IndexNotExist
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.IndicesCheckContinuation.{continue, stop}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.{CanPass, CheckContinuation}
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, KibanaIndexName}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

trait BaseIndicesProcessor {
  this: Logging =>

  protected def canPass[T <: ClusterIndexName : Matchable](requestContext: RequestContext,
                                                           determinedKibanaIndex: Option[KibanaIndexName],
                                                           requestedIndices: UniqueNonEmptyList[T])
                                                          (implicit indicesManager: IndicesManager[T]): Task[CanPass[Set[T]]] = {
    implicit val requestId: RequestContext.Id = requestContext.id
    if (requestContext.isReadOnlyRequest) canIndicesReadOnlyRequestPass(requestedIndices, determinedKibanaIndex)
    else canIndicesWriteRequestPass(requestedIndices, determinedKibanaIndex)
  }

  private def canIndicesReadOnlyRequestPass[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T],
                                                                               determinedKibanaIndex: Option[KibanaIndexName])
                                                                              (implicit requestId: RequestContext.Id,
                                                                               indicesManager: IndicesManager[T]): Task[CanPass[Set[T]]] = {
    val result = for {
      _ <- EitherT(allKibanaRelatedIndicesMatched(requestedIndices, determinedKibanaIndex))
      _ <- EitherT(noneOrAllIndicesMatched(requestedIndices))
      _ <- EitherT(allIndicesMatchedByWildcard(requestedIndices))
      _ <- EitherT(indicesAliasesDataStreams(requestedIndices))
    } yield ()
    result.value.map(_.left.getOrElse(CanPass.No()))
  }

  private def allKibanaRelatedIndicesMatched[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T],
                                                                                determinedKibanaIndex: Option[KibanaIndexName])
                                                                               (implicit requestId: RequestContext.Id): Task[CheckContinuation[Set[T]]] =
    Task.delay {
      determinedKibanaIndex match {
        case Some(kibanaIndexName) =>
          import KibanaIndexName._
          logger.debug(s"[${requestId.show}] Checking - all requested indices relate to Kibana indices ...")
          val allKibanaRelatedIndices = requestedIndices.forall(_.isRelatedToKibanaIndex(kibanaIndexName))
          if (allKibanaRelatedIndices) {
            logger.debug(s"[${requestId.show}] ... matched [indices: ${requestedIndices.map(_.show).mkString(",")}]. Stop")
            stop(CanPass.Yes(requestedIndices.toSet))
          } else {
            logger.debug(s"[${requestId.show}] ... not matched. Continue")
            continue[Set[T]]
          }
        case None =>
          continue[Set[T]]
      }
    }

  private def noneOrAllIndicesMatched[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])
                                                                        (implicit requestId: RequestContext.Id,
                                                                         indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = {
    logger.debug(s"[${requestId.show}] Checking - none or all indices ...")
    indicesManager
      .allIndicesAndAliasesAndDataStreams
      .map { allIndicesAndAliasesAndDataStreams =>
        logger.debug(s"[${requestId.show}] ... indices, aliases and data streams: [${allIndicesAndAliasesAndDataStreams.map(_.show).mkString(",")}]")
        if (requestedIndices.exists(_.allIndicesRequested)) {
          val allowedIndices = indicesManager.allowedIndicesMatcher.filter(allIndicesAndAliasesAndDataStreams)
          stop(
            if (allowedIndices.nonEmpty) {
              logger.debug(s"[${requestId.show}] ... matched [indices: ${requestedIndices.map(_.show).mkString(",")}]. Stop")
              CanPass.Yes(allowedIndices)
            } else {
              logger.debug(s"[${requestId.show}] ... not matched. Index not found. Stop")
              CanPass.No(IndexNotExist)
            }
          )
        } else {
          logger.debug(s"[${requestId.show}] ... not matched. Continue")
          continue[Set[T]]
        }
      }
  }

  private def allIndicesMatchedByWildcard[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])
                                                                            (implicit requestId: RequestContext.Id,
                                                                             indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = {
    logger.debug(s"[${requestId.show}] Checking if all indices are matched ...")
    Task.now {
      requestedIndices.toList match {
        case index :: Nil if !index.hasWildcard =>
          if (indicesManager.allowedIndicesMatcher.`match`(index)) {
            logger.debug(s"[${requestId.show}] ... matched [indices: ${index.show}]. Stop")
            stop(CanPass.Yes(Set(index)))
          } else {
            logger.debug(s"[${requestId.show}] ... not matched. Continue")
            continue
          }
        case _ if requestedIndices.forall(i => !i.hasWildcard) && indicesManager.allowedIndicesMatcher.filter(requestedIndices.toSet) === requestedIndices.toSet =>
          logger.debug(s"[${requestId.show}] ... matched [indices: ${requestedIndices.map(_.show).mkString(",")}]. Stop")
          stop(CanPass.Yes(requestedIndices.toSet))
        case _ =>
          logger.debug(s"[${requestId.show}] ... not matched. Continue")
          continue[Set[T]]
      }
    }
  }

  private def indicesAliasesDataStreams[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])
                                                                          (implicit requestId: RequestContext.Id,
                                                                           indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = {
    logger.debug(s"[${requestId.show}] Checking - indices & aliases & data streams...")
    Task
      .sequence(
        // indices requested
        filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(requestedIndices) ::
          filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured() ::
          filterAssumingThatIndicesAreRequestedAndDataStreamsAreConfigured(requestedIndices) ::
          filterAssumingThatIndicesAreRequestedAndDataStreamAliasesAreConfigured() ::
          // aliases requested
          filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured(requestedIndices) ::
          filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured(requestedIndices) ::
          filterAssumingThatAliasesAreRequestedAndDataStreamsAreConfigured(requestedIndices) ::
          filterAssumingThatAliasesAreRequestedAndDataStreamAliasesAreConfigured(requestedIndices) ::
          // data streams requested
          filterAssumingThatDataStreamsAreRequestedAndIndicesAreConfigured(requestedIndices) ::
          filterAssumingThatDataStreamsAreRequestedAndAliasesAreConfigured() ::
          filterAssumingThatDataStreamsAreRequestedAndDataStreamsAreConfigured(requestedIndices) ::
          filterAssumingThatDataStreamsAreRequestedAndDataStreamAliasesAreConfigured() :: Nil
      )
      .map(_.flatten.toSet)
      .map { allowedRealIndices =>
        if (allowedRealIndices.nonEmpty) {
          logger.debug(s"[${requestId.show}] ... matched [indices: ${allowedRealIndices.map(_.show).mkString(",")}]. Stop")
          stop(CanPass.Yes(allowedRealIndices))
        } else {
          logger.debug(s"[${requestId.show}] ... not matched. Stop!")
          stop(CanPass.No(Reason.IndexNotExist))
        }
      }
  }

  private def filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])
                                                                                                             (implicit indicesManager: IndicesManager[T]) = {
    indicesManager
      .allIndices
      .map { allIndices =>
        val resolvedRequestedIndices = PatternsMatcher.create(requestedIndices).filter(allIndices)
        indicesManager.allowedIndicesMatcher.filter(resolvedRequestedIndices)
      }
  }

  private def filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName]() = {
    // eg. alias A1 of index I1 can be defined with filtering, so result of /I1/_search will be different than
    // result of /A1/_search. It means that if indices are requested and aliases are configured, the result of
    // this kind of method will always be an empty set.
    Task.now(Set.empty[T])
  }

  private def filterAssumingThatIndicesAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])(implicit indicesManager: IndicesManager[T]) = {
    indicesManager
      .backingIndicesPerDataStreamMap
      .map { backingIndicesPerDataStream =>
        val requestedBackingIndicesMatcher = PatternsMatcher.create(requestedIndices)

        val requestedBackingIndicesPerDataStream: Map[T, UniqueNonEmptyList[T]] =
          backingIndicesPerDataStream
            .flatMap {
              case (dataStreamName, backingIndices) =>
                // we skip the backing indices for a given data stream when the data stream name matches the indices pattern too
                // (for indices pattern like (e.g. `*logs*`) the query would be exploded to logs_ds, .ds-logs_ds_0001, .ds-logs_ds_0002, etc.
                // passing the data stream name for such case is enough
                if (!requestedBackingIndicesMatcher.`match`(dataStreamName)) {
                  UniqueNonEmptyList.fromIterable(
                      requestedBackingIndicesMatcher.filter(backingIndices)
                    )
                    .map(indices => (dataStreamName, indices))
                } else {
                  Option.empty[(T, UniqueNonEmptyList[T])]
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
        val backingIndicesMatchedByAllowedBackingIndices =
          requestedBackingIndicesPerDataStream
            .flatMap {
              case (dataStreamName, backingIndices) => allowedMatcher.filter(backingIndices)
            }
            .toSet
        backingIndicesMatchedByAllowedDataStreamName ++ backingIndicesMatchedByAllowedBackingIndices
      }
  }

  private def filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])
                                                                                                             (implicit indicesManager: IndicesManager[T]) = {
    indicesManager
      .allAliases
      .map { allAliases =>
        val requestedAliasesNames = requestedIndices
        val requestedAliases = PatternsMatcher.create(requestedAliasesNames).filter(allAliases)

        indicesManager.allowedIndicesMatcher.filter(requestedAliases)
      }
  }

  private def filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])
                                                                                                             (implicit indicesManager: IndicesManager[T]) = {
    for {
      allAliases <- indicesManager.allAliases
      aliasesPerIndex <- indicesManager.indicesPerAliasMap
    } yield {
      val requestedAliasesNames = requestedIndices
      val requestedAliases = PatternsMatcher.create(requestedAliasesNames).filter(allAliases)

      val indicesOfRequestedAliases = requestedAliases.flatMap(aliasesPerIndex.getOrElse(_, Set.empty))
      indicesManager.allowedIndicesMatcher.filter(indicesOfRequestedAliases)
    }
  }

  private def filterAssumingThatAliasesAreRequestedAndDataStreamAliasesAreConfigured[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])
                                                                                                                       (implicit indicesManager: IndicesManager[T]) = {
    indicesManager
      .allDataStreamAliases
      .map { allDataStreamAliases =>
        val requestedAliasesNames = requestedIndices
        val requestedAliases = PatternsMatcher.create(requestedAliasesNames).filter(allDataStreamAliases)
        indicesManager.allowedIndicesMatcher.filter(requestedAliases)
      }
  }

  private def filterAssumingThatDataStreamsAreRequestedAndDataStreamAliasesAreConfigured[T <: ClusterIndexName : Matchable](): Task[Set[T]] = {
    // eg. alias A1 of data stream DS1 can be defined with filtering, so result of /DS1/_search will be different than
    // result of /A1/_search. It means that if data streams are requested and data stream aliases are configured, the result of
    // this kind of method will always be an empty set.
    Task.now(Set.empty[T])
  }

  private def filterAssumingThatIndicesAreRequestedAndDataStreamAliasesAreConfigured[T <: ClusterIndexName : Matchable](): Task[Set[T]] = {
    // eg. alias A1 of data stream DS1 with backing index I1 can be defined with filtering, so result of /I1/_search will be different than
    // result of /A1/_search. It means that if backing indices are requested and data stream aliases are configured, the result of
    // this kind of method will always be an empty set.
    Task.now(Set.empty[T])
  }

  private def filterAssumingThatAliasesAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])
                                                                                                                 (implicit indicesManager: IndicesManager[T]) = {
    for {
      allAliases <- indicesManager.allDataStreamAliases
      aliasesPerDataStream <- indicesManager.dataStreamsPerAliasMap
    } yield {
      val requestedAliasesNames = requestedIndices
      val requestedAliases = PatternsMatcher.create(requestedAliasesNames).filter(allAliases)
      val dataStreamsOfRequestedAliases = requestedAliases.flatMap(aliasesPerDataStream.getOrElse(_, Set.empty))
      indicesManager.allowedIndicesMatcher.filter(dataStreamsOfRequestedAliases)
    }
  }

  private def filterAssumingThatDataStreamsAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])
                                                                                                                 (implicit indicesManager: IndicesManager[T]): Task[Set[T]] = {
    for {
      allDataStreams <- indicesManager.allDataStreams
      backingIndicesPerDataStream <- indicesManager.backingIndicesPerDataStreamMap
    } yield {
      val requestedDataStreamsNames = requestedIndices
      val requestedDataStreams = PatternsMatcher.create(requestedDataStreamsNames).filter(allDataStreams)
      val allowedDataStreamsMatcher = indicesManager.allowedIndicesMatcher

      val indicesOfRequestedDataStream =
        requestedDataStreams
          .flatMap { dataStreamName =>
            if (!allowedDataStreamsMatcher.`match`(dataStreamName)) {
              backingIndicesPerDataStream.getOrElse(dataStreamName, Set.empty)
            } else {
              Set.empty[T]
            }
          }
      indicesManager.allowedIndicesMatcher.filter(indicesOfRequestedDataStream)
    }
  }

  private def filterAssumingThatDataStreamsAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName](): Task[Set[T]] = {
    // eg. alias A1 of data stream DS1 can be defined with filtering, so result of /DS1/_search will be different than
    // result of /A1/_search. It means that if data streams are requested and aliases are configured, the result of
    // this kind of method will always be an empty set.
    Task.now(Set.empty[T])
  }

  private def filterAssumingThatDataStreamsAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])
                                                                                                                     (implicit indicesManager: IndicesManager[T]): Task[Set[T]] = {
    indicesManager
      .allDataStreams
      .map { allDataStreams =>
        val requestedDataStreamsNames = requestedIndices
        val requestedDataStreams = PatternsMatcher.create(requestedDataStreamsNames).filter(allDataStreams)
        indicesManager.allowedIndicesMatcher.filter(requestedDataStreams)
      }
  }

  private def canIndicesWriteRequestPass[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T],
                                                                            determinedKibanaIndex: Option[KibanaIndexName])
                                                                           (implicit requestId: RequestContext.Id,
                                                                            indicesManager: IndicesManager[T]): Task[CanPass[Set[T]]] = {
    val result = for {
      _ <- EitherT(allKibanaRelatedIndicesMatched(requestedIndices, determinedKibanaIndex))
      _ <- EitherT(generalWriteRequest(requestedIndices))
    } yield ()
    result.value.map(_.left.getOrElse(CanPass.No()))
  }

  private def generalWriteRequest[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[T])
                                                                    (implicit requestId: RequestContext.Id,
                                                                     indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = Task.now {
    logger.debug(s"[${requestId.show}] Checking - write request ...")
    // Write requests
    logger.debug(s"[${requestId.show}] Stage 7")
    if (requestedIndices.isEmpty && indicesManager.allowedIndicesMatcher.contains("<no-index>")) {
      logger.debug(s"[${requestId.show}] ... matched [indices: ${requestedIndices.map(_.show).mkString(",")}]. Stop")
      stop(CanPass.Yes(requestedIndices.toSet))
    } else {
      // Reject write if at least one requested index is not allowed by the rule conf
      logger.debug(s"[${requestId.show}] Stage 8")
      stop {
        requestedIndices.find(index => !indicesManager.allowedIndicesMatcher.`match`(index)) match {
          case Some(_) =>
            logger.debug(s"[${requestId.show}] ... not matched. Stop")
            CanPass.No()
          case None =>
            logger.debug(s"[${requestId.show}] ... matched [indices: ${requestedIndices.map(_.show).mkString(",")}]. Stop")
            CanPass.Yes(requestedIndices.toSet)
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
}