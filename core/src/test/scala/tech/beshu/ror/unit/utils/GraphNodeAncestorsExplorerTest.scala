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
package tech.beshu.ror.unit.utils

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.utils.GraphNodeAncestorsExplorer

class GraphNodeAncestorsExplorerTest extends AnyWordSpec with Matchers with MockFactory {

  "Parents of 1 are" in {
    val graph = createDynamicallyTraversableGraph(nestedLevels = 2)
    graph.findAllAncestorsOf(Set("1")).runSyncUnsafe() should be (Set("2", "3", "4", "6"))
  }

  "Parents of 2 are" in {
    val graph = createDynamicallyTraversableGraph(nestedLevels = 1)
    graph.findAllAncestorsOf(Set("2")).runSyncUnsafe() should be (Set("4"))
  }

  private def createDynamicallyTraversableGraph(nestedLevels: Int Refined Positive) = {
    new GraphNodeAncestorsExplorer[String](nestedLevels, doFetchParentNodesOfMock())
  }

  private def doFetchParentNodesOfMock() = {
    /*
       ┌────┐
       │1   │
       └┬──┬┘
       ┌▽┐┌▽┐
       │3││2│
       └┬┘└┬┘
        │┌─▽──┐
        ││4   │
        │└┬──┬┘
       ┌▽─▽┐┌▽┐
       │6  ││5│
       └┬──┘└─┘
       ┌▽┐
       │7│
       └─┘
     */
    val mock = mockFunction[String, Task[Set[String]]]
    mock.expects("1").returns(Task.delay(Set("2", "3"))).noMoreThanOnce()
    mock.expects("2").returns(Task.delay(Set("4"))).noMoreThanOnce()
    mock.expects("3").returns(Task.delay(Set("6"))).noMoreThanOnce()
    mock.expects("4").returns(Task.delay(Set("5", "6"))).noMoreThanOnce()
    mock.expects("5").returns(Task.delay(Set.empty)).noMoreThanOnce()
    mock.expects("6").returns(Task.delay(Set("7"))).noMoreThanOnce()
    mock.expects("7").returns(Task.delay(Set.empty)).noMoreThanOnce()
    mock
  }
}
