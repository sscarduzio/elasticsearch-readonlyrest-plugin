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

import scala.annotation.nowarn
import cats.Show
import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass.No.Reason
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass.No.Reason.IndexNotExist
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.IndicesCheckContinuation.{continue, stop}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.{CanPass, CheckContinuation}
import tech.beshu.ror.accesscontrol.domain.Action.EsAction.restoreSnapshotAction
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, KibanaIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

@nowarn("msg=unused implicit parameter")
trait BaseIndicesProcessor {
  this: RequestIdAwareLogging =>

  import BaseIndicesProcessor.*

  protected def canPass[T <: ClusterIndexName : Matchable : Show](requestContext: RequestContext,
                                                                  determinedKibanaIndex: Option[KibanaIndexName],
                                                                  requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                 (implicit allowedIndicesManager: IndicesManager[T]): Task[CanPass[Set[RequestedIndex[T]]]] = {
    implicit val requestId: RequestId = requestContext.id.toRequestId
    if (shouldBeTreatedAsReadonlyRequest(requestContext))
      canIndicesReadOnlyRequestPass(requestedIndices, determinedKibanaIndex)
    else
      canIndicesWriteRequestPass(requestedIndices, determinedKibanaIndex)
  }

  private def shouldBeTreatedAsReadonlyRequest(requestContext: RequestContext) = {
    requestContext.action == restoreSnapshotAction || requestContext.isReadOnlyRequest
  }

  private def canIndicesReadOnlyRequestPass[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]],
                                                                                      determinedKibanaIndex: Option[KibanaIndexName])
                                                                                     (implicit requestId: RequestId,
                                                                                      allowedIndicesManager: IndicesManager[T]): Task[CanPass[Set[RequestedIndex[T]]]] = {
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
                                                                                      (implicit requestId: RequestId): Task[CheckContinuation[Set[RequestedIndex[T]]]] = {
    Task.delay {
      determinedKibanaIndex match {
        case Some(kibanaIndexName) =>
          import KibanaIndexName.*
          logger.debug(s"Checking - all requested indices relate to Kibana indices ...")
          val allKibanaRelatedIndices = requestedIndices.forall(_.name.isRelatedToKibanaIndex(kibanaIndexName))
          if (allKibanaRelatedIndices) {
            logger.debug(s"... matched [indices: ${requestedIndices.show}]. Stop")
            stop(CanPass.Yes(requestedIndices.toCovariantSet))
          } else {
            logger.debug(s"... not matched. Continue")
            continue[Set[RequestedIndex[T]]]
          }
        case None =>
          continue[Set[RequestedIndex[T]]]
      }
    }
  }

  private def noneOrAllIndicesMatched[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                               (implicit requestId: RequestId,
                                                                                allowedIndicesManager: IndicesManager[T]): Task[CheckContinuation[Set[RequestedIndex[T]]]] = {
    Task.delay {
      logger.debug(s"Checking - none or all indices ...")
    } >>
      (for {
        indicesAndAliases    <- allowedIndicesManager.allIndicesAndAliases
        dataStreamsAndAliases <- allowedIndicesManager.allDataStreamsAndDataStreamAliases
      } yield {
        logger.debug(s"... indices, aliases and data streams: [${(indicesAndAliases ++ dataStreamsAndAliases).show}]")
        if (requestedIndices.exists(_.name.allIndicesRequested)) {
          val allowedIndices = allowedIndicesManager.allowedIndicesMatcher
            .filter(indicesAndAliases.view ++ dataStreamsAndAliases)
          stop(
            if (allowedIndices.nonEmpty) {
              logger.debug(s"... matched [indices: ${requestedIndices.show}]. Stop")
              CanPass.Yes(allowedIndices.map(RequestedIndex(_, excluded = false)))
            } else {
              logger.debug(s"... not matched. Index not found. Stop")
              CanPass.No(IndexNotExist)
            }
          )
        } else {
          logger.debug(s"... not matched. Continue")
          continue[Set[RequestedIndex[T]]]
        }
      })
  }

  private def allIndicesMatchedByWildcard[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                   (implicit requestId: RequestId,
                                                                                    allowedIndicesManager: IndicesManager[T]): Task[CheckContinuation[Set[RequestedIndex[T]]]] =
    Task.delay {
      implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
      logger.debug(s"Checking if all indices are matched ...")
      requestedIndices.toList match {
        case requestedIndex :: Nil if !requestedIndex.name.hasWildcard =>
          if (allowedIndicesManager.allowedIndicesMatcher.`match`(requestedIndex)(_.name)) {
            logger.debug(s"... matched [indices: ${requestedIndex.show}]. Stop")
            stop(CanPass.Yes(Set(requestedIndex)))
          } else {
            logger.debug(s"... not matched. Continue")
            continue
          }
        case _ if requestedIndices.iterator.forall(i => !i.name.hasWildcard) &&
          allowedIndicesManager.allowedIndicesMatcher.filter(requestedIndices) == requestedIndices.toCovariantSet =>
          logger.debug(s"... matched [indices: ${requestedIndices.show}]. Stop")
          stop(CanPass.Yes(requestedIndices.toCovariantSet))
        case _ =>
          logger.debug(s"... not matched. Continue")
          continue[Set[RequestedIndex[T]]]
      }
    }

  private def indicesAliasesDataStreams[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                 (implicit requestId: RequestId,
                                                                                  allowedIndicesManager: IndicesManager[T]): Task[CheckContinuation[Set[RequestedIndex[T]]]] = {
    Task
      .delay(logger.debug(s"Checking - indices & aliases & data streams..."))
      .flatMap(_ => resolveRequestedNames(requestedIndices))
      .flatMap { resolvedRequestedNames =>
        Task.sequence(relevantResolutionTasks(requestedIndices, resolvedRequestedNames))
      }
      .map(_.flatten.toCovariantSet)
      .map { allowedRealIndices =>
        if (allowedRealIndices.nonEmpty) {
          logger.debug(s"... matched [indices: ${allowedRealIndices.show}]. Stop")
          stop(CanPass.Yes(allowedRealIndices))
        } else {
          logger.debug(s"... not matched. Stop!")
          stop(CanPass.No(Reason.IndexNotExist))
        }
      }
  }

  private def resolveRequestedNames[T <: ClusterIndexName : Matchable](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                       (implicit requestId: RequestId,
                                                                        allowedIndicesManager: IndicesManager[T]): Task[ResolvedRequestedNames[T]] = {
    val (excl, incl) = requestedIndices.toList.partition(_.excluded)
    val excludedMatcher = Option.when(excl.nonEmpty)(PatternsMatcher.create(excl.map(_.name)))
    val includedMatcher = Option.when(incl.nonEmpty)(PatternsMatcher.create(incl.map(_.name)))
    for {
      allIndices           <- allowedIndicesManager.allIndices
      allAliases           <- allowedIndicesManager.allAliases
      allDataStreams        <- allowedIndicesManager.allDataStreams
      allDataStreamAliases <- allowedIndicesManager.allDataStreamAliases
    } yield ResolvedRequestedNames(
      indices           = allIndices.filterBy(excludedMatcher, includedMatcher),
      aliases           = allAliases.filterBy(excludedMatcher, includedMatcher),
      dataStreams        = allDataStreams.filterBy(excludedMatcher, includedMatcher),
      dataStreamAliases = allDataStreamAliases.filterBy(excludedMatcher, includedMatcher)
    )
  }

  private def relevantResolutionTasks[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]],
                                                                                resolvedRequestedNames: ResolvedRequestedNames[T])
                                                                               (implicit requestId: RequestId,
                                                                                allowedIndicesManager: IndicesManager[T]): List[Task[Set[RequestedIndex[T]]]] = {
    val tasks = List.newBuilder[Task[Set[RequestedIndex[T]]]]

    if (resolvedRequestedNames.indices.nonEmpty) {
      tasks += filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(resolvedRequestedNames.indices)
    }
    // Backing indices (.ds-*) don't appear in allIndices so they're invisible to resolveRequestedNames.
    // Run the backing-index check when real indices were resolved (mixed request) or when nothing resolved
    // at all (pure backing-index request).
    if (resolvedRequestedNames.indices.nonEmpty || resolvedRequestedNames.isEmpty) {
      tasks += filterAssumingThatIndicesAreRequestedAndDataStreamsAreConfigured(requestedIndices)
    }

    if (resolvedRequestedNames.aliases.nonEmpty) {
      tasks += filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured(resolvedRequestedNames.aliases)
      tasks += filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured(resolvedRequestedNames.aliases)
    }

    if (resolvedRequestedNames.dataStreams.nonEmpty) {
      tasks += filterAssumingThatDataStreamsAreRequestedAndIndicesAreConfigured(resolvedRequestedNames.dataStreams)
      tasks += filterAssumingThatDataStreamsAreRequestedAndDataStreamsAreConfigured(resolvedRequestedNames.dataStreams)
    }

    if (resolvedRequestedNames.dataStreamAliases.nonEmpty) {
      tasks += filterAssumingThatAliasesAreRequestedAndDataStreamsAreConfigured(resolvedRequestedNames.dataStreamAliases)
      tasks += filterAssumingThatAliasesAreRequestedAndDataStreamAliasesAreConfigured(resolvedRequestedNames.dataStreamAliases)
    }

    tasks.result()
  }

  private def filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : Matchable : Show](resolvedRequestedIndices: Set[RequestedIndex[T]])
                                                                                                                    (implicit requestId: RequestId,
                                                                                                                     allowedIndicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    Task.delay {
      implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
      allowedIndicesManager.allowedIndicesMatcher.filter(resolvedRequestedIndices)
    }
  }

  private def filterAssumingThatIndicesAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                                                                        (implicit requestId: RequestId,
                                                                                                                         allowedIndicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    allowedIndicesManager
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
        val allowedMatcher = allowedIndicesManager.allowedIndicesMatcher
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

  private def filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedAliases: Set[RequestedIndex[T]])
                                                                                                                    (implicit requestId: RequestId,
                                                                                                                     allowedIndicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    Task.delay {
      implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
      allowedIndicesManager.allowedIndicesMatcher.filter(requestedAliases)
    }
  }

  private def filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedAliases: Set[RequestedIndex[T]])
                                                                                                                     (implicit requestId: RequestId,
                                                                                                                      allowedIndicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    if (requestedAliases.isEmpty) {
      Task.now(Set.empty)
    } else {
      allowedIndicesManager.indicesPerAliasMap.map { aliasesPerIndex =>
        implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
        allowedIndicesManager.allowedIndicesMatcher.filter(
          requestedAliases.iterator.flatMap { requestedAlias =>
            aliasesPerIndex
              .getOrElse(requestedAlias.name, Set.empty)
              .iterator
              .map(alias => RequestedIndex(alias, requestedAlias.excluded))
          }
        )
      }
    }
  }

  private def filterAssumingThatAliasesAreRequestedAndDataStreamAliasesAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedAliases: Set[RequestedIndex[T]])
                                                                                                                              (implicit requestId: RequestId,
                                                                                                                               allowedIndicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    Task.delay {
      implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
      allowedIndicesManager.allowedIndicesMatcher.filter(requestedAliases)
    }
  }

  private def filterAssumingThatAliasesAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedAliases: Set[RequestedIndex[T]])
                                                                                                                         (implicit requestId: RequestId,
                                                                                                                          allowedIndicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    if (requestedAliases.isEmpty) {
      Task.now(Set.empty)
    } else {
      allowedIndicesManager.dataStreamsPerAliasMap.map { aliasesPerDataStream =>
        implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
        allowedIndicesManager.allowedIndicesMatcher.filter(
          requestedAliases.iterator.flatMap { requestedAlias =>
            aliasesPerDataStream
              .getOrElse(requestedAlias.name, Set.empty)
              .iterator
              .map(alias => RequestedIndex(alias, requestedAlias.excluded))
          }
        )
      }
    }
  }

  private def filterAssumingThatDataStreamsAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedDataStreams: Set[RequestedIndex[T]])
                                                                                                                         (implicit requestId: RequestId,
                                                                                                                          allowedIndicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    val allowedDataStreamsMatcher = allowedIndicesManager.allowedIndicesMatcher
    val requestedDataStreamsNotAllowedByName = requestedDataStreams.filterNot(requestedDataStream =>
      allowedDataStreamsMatcher.`match`(requestedDataStream.name)
    )

    if (requestedDataStreamsNotAllowedByName.isEmpty) {
      Task.now(Set.empty)
    } else {
      allowedIndicesManager.backingIndicesPerDataStreamMap.map { backingIndicesPerDataStream =>
        val indicesOfRequestedDataStream = requestedDataStreamsNotAllowedByName
          .flatMap { requestedDataStreamName =>
            backingIndicesPerDataStream
              .getOrElse(requestedDataStreamName.name, Set.empty)
              .map(backingIndex => RequestedIndex(backingIndex, requestedDataStreamName.excluded))
          }

        implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name.asInstanceOf[T])
        allowedIndicesManager.allowedIndicesMatcher.filter(indicesOfRequestedDataStream)
      }
    }
  }

  private def filterAssumingThatDataStreamsAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName : Matchable : Show](requestedDataStreams: Set[RequestedIndex[T]])
                                                                                                                            (implicit requestId: RequestId,
                                                                                                                             allowedIndicesManager: IndicesManager[T]): Task[Set[RequestedIndex[T]]] = {
    Task.delay {
      implicit val conversion: PatternsMatcher[T]#Conversion[RequestedIndex[T]] = PatternsMatcher.Conversion.from(_.name)
      allowedIndicesManager.allowedIndicesMatcher.filter(requestedDataStreams)
    }
  }

  private def canIndicesWriteRequestPass[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]],
                                                                                   determinedKibanaIndex: Option[KibanaIndexName])
                                                                                  (implicit requestId: RequestId,
                                                                                   allowedIndicesManager: IndicesManager[T]): Task[CanPass[Set[RequestedIndex[T]]]] = {
    val result = for {
      _ <- EitherT(allKibanaRelatedIndicesMatched(requestedIndices, determinedKibanaIndex))
      _ <- EitherT(generalWriteRequest(requestedIndices))
    } yield ()
    result.value.map(_.left.getOrElse(CanPass.No()))
  }

  private def generalWriteRequest[T <: ClusterIndexName : Matchable : Show](requestedIndices: UniqueNonEmptyList[RequestedIndex[T]])
                                                                           (implicit requestId: RequestId,
                                                                            allowedIndicesManager: IndicesManager[T]): Task[CheckContinuation[Set[RequestedIndex[T]]]] = {
    Task.delay {
      logger.debug(s"Checking - write request ...")
      // Write requests
      logger.debug(s"Stage 7")
      if (requestedIndices.isEmpty && allowedIndicesManager.allowedIndicesMatcher.contains("<no-index>")) {
        logger.debug(s"... matched [indices: ${requestedIndices.show}]. Stop")
        stop(CanPass.Yes(requestedIndices.toCovariantSet))
      } else {
        // Reject write if at least one requested index is not allowed by the rule conf
        logger.debug(s"Stage 8")
        stop {
          requestedIndices.find(requestedIndex => !allowedIndicesManager.allowedIndicesMatcher.`match`(requestedIndex.name)) match {
            case Some(_) =>
              logger.debug(s"... not matched. Stop")
              CanPass.No()
            case None =>
              logger.debug(s"... matched [indices: ${requestedIndices.show}]. Stop")
              CanPass.Yes(requestedIndices.toCovariantSet)
          }
        }
      }
    }
  }

}

object BaseIndicesProcessor {

  private final case class ResolvedRequestedNames[T <: ClusterIndexName](indices: Set[RequestedIndex[T]],
                                                                         aliases: Set[RequestedIndex[T]],
                                                                         dataStreams: Set[RequestedIndex[T]],
                                                                         dataStreamAliases: Set[RequestedIndex[T]]) {
    def isEmpty: Boolean = indices.isEmpty && aliases.isEmpty && dataStreams.isEmpty && dataStreamAliases.isEmpty
  }

  trait IndicesManager[T <: ClusterIndexName] {
    final def allIndicesAndAliasesAndDataStreams(implicit requestId: RequestId): Task[Set[T]] = {
      for {
        indicesAndAliases <- allIndicesAndAliases
        dataStreamsAndAliases <- allDataStreamsAndDataStreamAliases
      } yield indicesAndAliases ++ dataStreamsAndAliases
    }

    // indices and aliases
    def allIndicesAndAliases(implicit id: RequestId): Task[Set[T]]

    def allIndices(implicit id: RequestId): Task[Set[T]]

    def allAliases(implicit id: RequestId): Task[Set[T]]

    def indicesPerAliasMap(implicit id: RequestId): Task[Map[T, Set[T]]]

    // data streams and their aliases
    def allDataStreamsAndDataStreamAliases(implicit id: RequestId): Task[Set[T]]

    def allDataStreams(implicit id: RequestId): Task[Set[T]]

    def allDataStreamAliases(implicit id: RequestId): Task[Set[T]]

    def dataStreamsPerAliasMap(implicit id: RequestId): Task[Map[T, Set[T]]]

    def backingIndicesPerDataStreamMap(implicit id: RequestId): Task[Map[T, Set[T]]]

    def allowedIndicesMatcher: PatternsMatcher[T]
  }
}
