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

class DynamicallyTraversableGraph[NODE](nestedLevels: Int Refined Positive,
                                        doFetchParentNodesOf: NODE => Task[Set[NODE]]) {

  def findAllParentsOf(nodes: Iterable[NODE]): Task[Set[NODE]] = {
    findAllParentsOf(nodes, Atomic(Set.empty[NODE]), nestedLevel = nestedLevels.value)
  }

  private def findAllParentsOf(nodes: Iterable[NODE],
                               processedNodes: Atomic[Set[NODE]],
                               nestedLevel: Int): Task[Set[NODE]] = {
    if (nestedLevel > 0) {
      Task
        .parSequenceUnordered {
          nodes.toList.map(fetchParentNodesOf(_, processedNodes, nestedLevel))
        }
        .map(_.flatten.toSet)
    } else {
      Task.now(Set.empty)
    }
  }

  private def fetchParentNodesOf(node: NODE,
                                 processedNodes: Atomic[Set[NODE]],
                                 nestedLevel: Int): Task[Set[NODE]] = {
    import DynamicallyTraversableGraph.ProcessingState
    val processingState = processedNodes.transformAndExtract { alreadyProcessedNodes =>
      if (alreadyProcessedNodes.contains(node)) (ProcessingState.AlreadyProcessed, alreadyProcessedNodes)
      else (ProcessingState.ToBeProcessed, alreadyProcessedNodes + node)
    }
    processingState match {
      case ProcessingState.AlreadyProcessed =>
        Task.now(Set.empty)
      case ProcessingState.ToBeProcessed =>
        doFetchParentNodesOf(node)
          .flatMap { newGroups =>
            findAllParentsOf(newGroups, processedNodes, nestedLevel - 1)
              .map(_ ++ newGroups)
          }
    }
  }
}
private object DynamicallyTraversableGraph {

  sealed trait ProcessingState
  object ProcessingState {
    case object AlreadyProcessed extends ProcessingState
    case object ToBeProcessed extends ProcessingState
  }
}