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
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.{CanPass, CheckContinuation}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.IndicesCheckContinuation.{continue, stop}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.matchers.{IndicesMatcher, MatcherWithWildcardsScalaAdapter}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.CaseMappingEquality
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

trait BaseIndicesProcessor {
  this: Logging =>

  protected def canPass[T <: ClusterIndexName : CaseMappingEquality](requestContext: RequestContext,
                                                                     indices: UniqueNonEmptyList[T])
                                                                    (implicit indicesManager: IndicesManager[T]): Task[CanPass[Set[T]]] = {
    implicit val requestId: RequestContext.Id = requestContext.id
    if (requestContext.isReadOnlyRequest) canIndicesReadOnlyRequestPass(indices)
    else canIndicesWriteRequestPass(indices)
  }

  private def canIndicesReadOnlyRequestPass[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                                        (implicit requestId: RequestContext.Id,
                                                                                         indicesManager: IndicesManager[T]): Task[CanPass[Set[T]]] = {
    val result = for {
      _ <- EitherT(noneOrAllIndices(indices))
      _ <- EitherT(allIndicesMatchedByWildcard(indices))
      _ <- EitherT(indicesAliasesDataStreams(indices))
    } yield ()
    result.value.map(_.left.getOrElse(CanPass.No()))
  }

  private def noneOrAllIndices[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                           (implicit requestId: RequestContext.Id,
                                                                            indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = {
    logger.debug(s"[${requestId.show}] Checking - none or all indices ...")
    indicesManager
      .allIndicesAndAliasesAndDataStreams
      .map { allIndicesAndAliasesAndDataStreams =>
        logger.debug(s"[${requestId.show}] ... indices, aliases and data streams: [${allIndicesAndAliasesAndDataStreams.map(_.show).mkString(",")}]")
        if (indices.exists(_.allIndicesRequested)) {
          val allowedIndices = indicesManager.matcher.filterIndices(allIndicesAndAliasesAndDataStreams)
          stop(
            if (allowedIndices.nonEmpty) {
              logger.debug(s"[${requestId.show}] ... matched [indices: ${indices.map(_.show).mkString(",")}]. Stop")
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

  private def allIndicesMatchedByWildcard[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                                      (implicit requestId: RequestContext.Id,
                                                                                       indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = {
    logger.debug(s"[${requestId.show}] Checking if all indices are matched ...")
    Task.now {
      indices.toList match {
        case index :: Nil if !index.hasWildcard =>
          if (indicesManager.matcher.`match`(index)) {
            logger.debug(s"[${requestId.show}] ... matched [indices: ${index.show}]. Stop")
            stop(CanPass.Yes(Set(index)))
          } else {
            logger.debug(s"[${requestId.show}] ... not matched. Continue")
            continue
          }
        case _ if indices.forall(i => !i.hasWildcard) && indicesManager.matcher.filterIndices(indices.toSet) === indices.toSet =>
          logger.debug(s"[${requestId.show}] ... matched [indices: ${indices.map(_.show).mkString(",")}]. Stop")
          stop(CanPass.Yes(indices.toSet))
        case _ =>
          logger.debug(s"[${requestId.show}] ... not matched. Continue")
          continue[Set[T]]
      }
    }
  }

  private def indicesAliasesDataStreams[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                                    (implicit requestId: RequestContext.Id,
                                                                                     indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = {
    logger.debug(s"[${requestId.show}] Checking - indices & aliases & data streams...")
    Task
      .sequence(
        // indices requested
        filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(indices) ::
          filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured() ::
          filterAssumingThatIndicesAreRequestedAndDataStreamsAreConfigured() ::
          // aliases requested
          filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured(indices) ::
          filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured(indices) ::
          filterAssumingThatAliasesAreRequestedAndDataStreamsAreConfigured(indices) ::
          // data streams requested
          filterAssumingThatDataStreamsAreRequestedAndIndicesAreConfigured(indices) ::
          filterAssumingThatDataStreamsAreRequestedAndAliasesAreConfigured() ::
          filterAssumingThatDataStreamsAreRequestedAndDataStreamsAreConfigured(indices) :: Nil
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

  private def filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                                                                       (implicit indicesManager: IndicesManager[T]) = {
    indicesManager
      .allIndices
      .map { allIndices =>
        val requestedIndicesNames = indices
        val requestedIndices = MatcherWithWildcardsScalaAdapter.create(requestedIndicesNames).filter(allIndices)

        indicesManager.matcher.filterIndices(requestedIndices)
      }
  }

  private def filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName]() = {
    // eg. alias A1 of index I1 can be defined with filtering, so result of /I1/_search will be different than
    // result of /A1/_search. It means that if indices are requested and aliases are configured, the result of
    // this kind of method will always be an empty set.
    Task.now(Set.empty[T])
  }

  private def filterAssumingThatIndicesAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName]() = {
    // this case already handled by 'allIndices' - it already contains backing indices of data streams
    Task.now(Set.empty[T])
  }

  private def filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                                                                       (implicit indicesManager: IndicesManager[T]) = {
    indicesManager
      .allAliases
      .map { allAliases =>
        val requestedAliasesNames = indices
        val requestedAliases = MatcherWithWildcardsScalaAdapter.create(requestedAliasesNames).filter(allAliases)

        indicesManager.matcher.filterIndices(requestedAliases)
      }
  }

  private def filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                                                                       (implicit indicesManager: IndicesManager[T]) = {
    for {
      allAliases <- indicesManager.allAliases
      aliasesPerIndex <- indicesManager.indicesPerAliasMap
    } yield {
      val requestedAliasesNames = indices
      val requestedAliases = MatcherWithWildcardsScalaAdapter.create(requestedAliasesNames).filter(allAliases)

      val indicesOfRequestedAliases = requestedAliases.flatMap(aliasesPerIndex.getOrElse(_, Set.empty))
      indicesManager.matcher.filterIndices(indicesOfRequestedAliases)
    }
  }

  private def filterAssumingThatAliasesAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                                                                           (implicit indicesManager: IndicesManager[T]) = {
    for {
      allAliases <- indicesManager.allDataStreamAliases
      aliasesPerDataStream <- indicesManager.dataStreamsPerAliasMap
    } yield {
      val requestedAliasesNames = indices
      val requestedAliases = MatcherWithWildcardsScalaAdapter.create(requestedAliasesNames).filter(allAliases)
      val dataStreamsOfRequestedAliases = requestedAliases.flatMap(aliasesPerDataStream.getOrElse(_, Set.empty))
      indicesManager.matcher.filterIndices(dataStreamsOfRequestedAliases)
    }
  }

  private def filterAssumingThatDataStreamsAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                                                                           (implicit indicesManager: IndicesManager[T]): Task[Set[T]] = {
    for {
      allDataStreams <- indicesManager.allDataStreams
      backingIndicesPerDataStream <- indicesManager.indicesPerDataStreamMap
    } yield {
      val requestedDataStreamsNames = indices
      val requestedDataStreams = MatcherWithWildcardsScalaAdapter.create(requestedDataStreamsNames).filter(allDataStreams)
      val indicesOfRequestedDataStream = requestedDataStreams.flatMap(backingIndicesPerDataStream.getOrElse(_, Set.empty))
      indicesManager.matcher.filterIndices(indicesOfRequestedDataStream)
    }
  }

  private def filterAssumingThatDataStreamsAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName](): Task[Set[T]] = {
    // eg. alias A1 of data stream DS1 can be defined with filtering, so result of /DS1/_search will be different than
    // result of /A1/_search. It means that if data streams are requested and aliases are configured, the result of
    // this kind of method will always be an empty set.
    Task.now(Set.empty[T])
  }

  private def filterAssumingThatDataStreamsAreRequestedAndDataStreamsAreConfigured[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                                                                               (implicit indicesManager: IndicesManager[T]): Task[Set[T]] = {
    indicesManager
      .allDataStreams
      .map { allDataStreams =>
        val requestedDataStreamsNames = indices
        val requestedDataStreams = MatcherWithWildcardsScalaAdapter.create(requestedDataStreamsNames).filter(allDataStreams)
        indicesManager.matcher.filterIndices(requestedDataStreams)
      }
  }

  private def canIndicesWriteRequestPass[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                                     (implicit requestId: RequestContext.Id,
                                                                                      indicesManager: IndicesManager[T]): Task[CanPass[Set[T]]] = {
    val result = for {
      _ <- EitherT(generalWriteRequest(indices))
    } yield ()
    result.value.map(_.left.getOrElse(CanPass.No()))
  }

  private def generalWriteRequest[T <: ClusterIndexName : CaseMappingEquality](indices: UniqueNonEmptyList[T])
                                                                              (implicit requestId: RequestContext.Id,
                                                                               indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = Task.now {
    logger.debug(s"[${requestId.show}] Checking - write request ...")
    // Write requests
    logger.debug(s"[${requestId.show}] Stage 7")
    if (indices.isEmpty && indicesManager.matcher.contains("<no-index>")) {
      logger.debug(s"[${requestId.show}] ... matched [indices: ${indices.map(_.show).mkString(",")}]. Stop")
      stop(CanPass.Yes(indices.toSet))
    } else {
      // Reject write if at least one requested index is not allowed by the rule conf
      logger.debug(s"[${requestId.show}] Stage 8")
      stop {
        indices.find(index => !indicesManager.matcher.`match`(index)) match {
          case Some(_) =>
            logger.debug(s"[${requestId.show}] ... not matched. Stop")
            CanPass.No()
          case None =>
            logger.debug(s"[${requestId.show}] ... matched [indices: ${indices.map(_.show).mkString(",")}]. Stop")
            CanPass.Yes(indices.toSet)
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
    def indicesPerDataStreamMap: Task[Map[T, Set[T]]]

    def matcher: IndicesMatcher[T]
  }
}