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
package tech.beshu.ror.utils

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import monix.execution.atomic.Atomic

class GraphNodeAncestorsExplorer[NODE](kinshipLevel: Int Refined Positive,
                                       doFetchParentNodesOf: NODE => Task[Set[NODE]]) {

  def findAllAncestorsOf(nodes: Iterable[NODE]): Task[Set[NODE]] = {
    findAllAncestorsOf(nodes, Atomic(Set.empty[NODE]), kinshipLevel = kinshipLevel.value)
  }

  private def findAllAncestorsOf(nodes: Iterable[NODE],
                                 processedNodes: Atomic[Set[NODE]],
                                 kinshipLevel: Int): Task[Set[NODE]] = {
    if (kinshipLevel > 0) {
      Task
        .parSequenceUnordered {
          nodes.toList.map(fetchAncestorsOf(_, processedNodes, kinshipLevel))
        }
        .map(_.flatten.toSet)
    } else {
      Task.now(Set.empty)
    }
  }

  private def fetchAncestorsOf(node: NODE,
                               processedNodes: Atomic[Set[NODE]],
                               kinshipLevel: Int): Task[Set[NODE]] = {
    import GraphNodeAncestorsExplorer.ProcessingState
    val processingState = processedNodes.transformAndExtract { alreadyProcessedNodes =>
      if (alreadyProcessedNodes.contains(node)) (ProcessingState.AlreadyProcessed, alreadyProcessedNodes)
      else (ProcessingState.ToBeProcessed, alreadyProcessedNodes + node)
    }
    processingState match {
      case ProcessingState.AlreadyProcessed =>
        Task.now(Set.empty)
      case ProcessingState.ToBeProcessed =>
        doFetchParentNodesOf(node)
          .flatMap { parentNodes =>
            findAllAncestorsOf(parentNodes, processedNodes, kinshipLevel - 1)
              .map(_ ++ parentNodes)
          }
    }
  }
}
private object GraphNodeAncestorsExplorer {

  sealed trait ProcessingState
  object ProcessingState {
    case object AlreadyProcessed extends ProcessingState
    case object ToBeProcessed extends ProcessingState
  }
}