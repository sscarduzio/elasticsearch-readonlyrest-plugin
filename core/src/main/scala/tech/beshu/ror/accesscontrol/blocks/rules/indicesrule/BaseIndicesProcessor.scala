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
package tech.beshu.ror.accesscontrol.blocks.rules.indicesrule

import cats.data.EitherT
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.BaseIndicesProcessor.IndicesManager
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain.CanPass.No.Reason
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain.IndicesCheckContinuation.{continue, stop}
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain.{CanPass, CheckContinuation}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.matchers.{IndicesMatcher, MatcherWithWildcardsScalaAdapter}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.CaseMappingEquality

trait BaseIndicesProcessor {
  this: IndicesRule =>

  protected def canPass[T <: ClusterIndexName : CaseMappingEquality](requestContext: RequestContext,
                                                                     indices: Set[T])
                                                                    (implicit indicesManager: IndicesManager[T]): Task[CanPass[Set[T]]] = {
    if (requestContext.isReadOnlyRequest) canIndicesReadOnlyRequestPass(indices)
    else canIndicesWriteRequestPass(indices)
  }

  private def canIndicesReadOnlyRequestPass[T <: ClusterIndexName : CaseMappingEquality](indices: Set[T])
                                                                                        (implicit indicesManager: IndicesManager[T]): Task[CanPass[Set[T]]] = {
    val result = for {
      _ <- EitherT(noneOrAllIndices(indices))
      _ <- EitherT(allIndicesMatchedByWildcard(indices))
      _ <- EitherT(indicesAliases(indices))
    } yield ()
    result.value.map(_.left.getOrElse(CanPass.No()))
  }

  private def noneOrAllIndices[T <: ClusterIndexName : CaseMappingEquality](indices: Set[T])
                                                                           (implicit indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = {
    logger.debug(s"[{requestContext.id.show}] Checking - none or all indices ...") //todo: id
    indicesManager
      .allIndicesAndAliases
      .map { allIndicesAndAliases =>
        if (indices.isEmpty) { // || indices.contains(T.all) || indices.contains(T.wildcard)) { // todo:
          val allowedIdxs = indicesManager.matcher.filterIndices(allIndicesAndAliases)
          stop(
            if (allowedIdxs.nonEmpty) CanPass.Yes(allowedIdxs)
            else CanPass.No(Reason.IndexNotExist)
          )
        } else {
          continue[Set[T]]
        }
      }
  }

  private def allIndicesMatchedByWildcard[T <: ClusterIndexName : CaseMappingEquality](indices: Set[T])
                                                                                      (implicit indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = {
    logger.debug(s"[{requestContext.id.show}] Checking if all indices are matched ...") // todo: id
    Task.now {
      indices.toList match {
        case index :: Nil if !index.hasWildcard =>
          if (indicesManager.matcher.`match`(index)) {
            stop(CanPass.Yes(Set(index)))
          } else {
            continue
          }
        case _ if indices.forall(i => !i.hasWildcard) && indicesManager.matcher.filterIndices(indices) === indices =>
          stop(CanPass.Yes(indices))
        case _ =>
          continue[Set[T]]
      }
    }
  }

  private def indicesAliases[T <: ClusterIndexName : CaseMappingEquality](indices: Set[T])
                                                                         (implicit indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = {
    logger.debug(s"[{requestContext.id.show}] Checking - indices & aliases ...") // todo: id
    Task
      .sequence(
        filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(indices) ::
          filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured() ::
          filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured(indices) ::
          filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured(indices) :: Nil
      )
      .map(_.flatten.toSet)
      .map { allowedRealIndices =>
        if (allowedRealIndices.nonEmpty) {
          stop(CanPass.Yes(allowedRealIndices))
        } else {
          stop(CanPass.No(Reason.IndexNotExist))
        }
      }
  }

  private def filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : CaseMappingEquality](indices: Set[T])
                                                                                                                       (implicit indicesManager: IndicesManager[T]) = {
    indicesManager
      .allIndices
      .map { allIndices =>
        val requestedIndicesNames = indices
        val requestedIndices = MatcherWithWildcardsScalaAdapter.create(requestedIndicesNames).filter(allIndices)

        indicesManager.matcher.filterIndices(requestedIndices)
      }
  }

  private def filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName : CaseMappingEquality]() = {
    // eg. alias A1 of index I1 can be defined with filtering, so result of /I1/_search will be different than
    // result of /A1/_search. It means that if indices are requested and aliases are configured, the result of
    // this kind of method will always be an empty set.
    Task.now(Set.empty[T])
  }

  private def filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured[T <: ClusterIndexName : CaseMappingEquality](indices: Set[T])
                                                                                                                       (implicit indicesManager: IndicesManager[T]) = {
    indicesManager
      .allAliases
      .map { allAliases =>
        val requestedAliasesNames = indices
        val requestedAliases = MatcherWithWildcardsScalaAdapter.create(requestedAliasesNames).filter(allAliases)

        indicesManager.matcher.filterIndices(requestedAliases)
      }
  }

  private def filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured[T <: ClusterIndexName : CaseMappingEquality](indices: Set[T])
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

  private def canIndicesWriteRequestPass[T <: ClusterIndexName : CaseMappingEquality](indices: Set[T])
                                                                                     (implicit indicesManager: IndicesManager[T]): Task[CanPass[Set[T]]] = {
    val result = for {
      _ <- EitherT(generalWriteRequest(indices))
    } yield ()
    result.value.map(_.left.getOrElse(CanPass.No()))
  }

  private def generalWriteRequest[T <: ClusterIndexName : CaseMappingEquality](indices: Set[T])
                                                                              (implicit indicesManager: IndicesManager[T]): Task[CheckContinuation[Set[T]]] = Task.now {
    logger.debug(s"[{requestContext.id.show}] Checking - write request ...") //todo: id
    // Write requests
    logger.debug(s"[{requestContext.id.show}] Stage 7") //todo: id
    if (indices.isEmpty && indicesManager.matcher.contains("<no-index>")) {
      stop(CanPass.Yes(indices))
    } else {
      // Reject write if at least one requested index is not allowed by the rule conf
      logger.debug(s"[{requestContext.id.show}] Stage 8") //todo: id
      stop {
        indices.find(index => !indicesManager.matcher.`match`(index)) match {
          case Some(_) => CanPass.No()
          case None => CanPass.Yes(indices)
        }
      }
    }
  }
}

object BaseIndicesProcessor {

  trait IndicesManager[T <: ClusterIndexName] {
    def allIndicesAndAliases: Task[Set[T]]

    def allIndices: Task[Set[T]]

    def allAliases: Task[Set[T]]

    def indicesPerAliasMap: Task[Map[T, Set[T]]]

    def matcher: IndicesMatcher[T]
  }
}
