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

import com.dimafeng.testcontainers.Container
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.util.control.NonFatal

object ContainersCleanup {

  // Starts the containers in parallel and waits until every start ends, also after one fails. A stop
  // that runs while a start is still in progress does not see that container, and it stays running.
  // Throws the first failure, with the other failures attached as suppressed.
  def startAllInParallel(containers: List[Container]): Unit = {
    val results = Task
      .parTraverseUnordered(containers)(container => Task(container.start()).attempt)
      .runSyncUnsafe()
    throwFirst(results.collect { case Left(failure) => failure })
  }

  // Runs every stop action, also after one of them fails. Throws the first failure, with the other
  // failures attached as suppressed.
  def stopAll(stopActions: List[() => Unit]): Unit = {
    throwFirst(stopActions.flatMap { stopAction =>
      try {
        stopAction()
        None
      } catch {
        case NonFatal(ex) => Some(ex)
      }
    })
  }

  // Runs `start`. When it fails, runs `stop` and throws the start failure. A stop failure is
  // attached to it as suppressed, so the start failure stays the reported cause.
  def stopAllWhenStartFails(stop: => Unit)(start: => Unit): Unit = {
    try start
    catch {
      case NonFatal(startFailure) =>
        try stop
        catch {
          case NonFatal(stopFailure) => startFailure.addSuppressed(stopFailure)
        }
        throw startFailure
    }
  }

  private def throwFirst(failures: List[Throwable]): Unit = failures match {
    case Nil           => ()
    case first :: rest =>
      rest.foreach(first.addSuppressed)
      throw first
  }

}

// Starts the containers in order. When one does not start, stops all of them and throws. This is
// MultipleContainers of testcontainers-scala plus the cleanup: there, a failed start leaves the
// containers that started before it running.
// The containers are created on the first start(), not before.
class AllOrNothingContainers(createContainers: => List[Container]) extends Container {

  private lazy val containers = createContainers

  override def start(): Unit =
    ContainersCleanup.stopAllWhenStartFails(stop())(containers.foreach(_.start()))

  override def stop(): Unit =
    ContainersCleanup.stopAll(containers.map(container => () => container.stop()))

}
