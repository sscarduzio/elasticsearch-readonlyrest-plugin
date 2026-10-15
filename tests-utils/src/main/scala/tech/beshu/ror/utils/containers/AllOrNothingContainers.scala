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
package tech.beshu.ror.utils.containers

import cats.data.NonEmptyList
import com.dimafeng.testcontainers.Container
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

// Starts the containers. When one does not start, stops all of them and throws. This is
// MultipleContainers of testcontainers-scala plus the cleanup: there, a failed start leaves the
// containers that started before it running.
// The containers are created on the first start(), not before.
class AllOrNothingContainers private (createContainers: => NonEmptyList[Container], parallel: Boolean)
    extends StoppedOnFailedStart {

  private lazy val containers = createContainers

  override protected def startParts(): Unit =
    if (parallel) startAllInParallel() else containers.toList.foreach(_.start())

  override protected def stopActions: List[() => Unit] =
    containers.toList.map(container => () => container.stop())

  // Waits until every start ends, also after one fails. A stop that runs while a start is still in
  // progress does not see that container, and it stays running.
  private def startAllInParallel(): Unit = {
    val results = Task
      .parTraverseUnordered(containers.toList)(container => Task(container.start()).attempt)
      .runSyncUnsafe()
    StoppedOnFailedStart.throwFirst(results.collect { case Left(failure) => failure })
  }

}

object AllOrNothingContainers {

  def inOrder(createContainers: => NonEmptyList[Container]): AllOrNothingContainers =
    new AllOrNothingContainers(createContainers, parallel = false)

  def inParallel(createContainers: => NonEmptyList[Container]): AllOrNothingContainers =
    new AllOrNothingContainers(createContainers, parallel = true)

}
