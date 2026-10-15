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

import scala.util.control.NonFatal

// A container made of parts. When startParts() fails, start() stops the parts that started and
// throws the start failure. The caller cannot do it: ForAllTestContainer calls stop() only after
// start() returns.
// stop() runs every stop action, also after one of them fails, and then throws the first failure.
// The other failures are attached to it as suppressed.
trait StoppedOnFailedStart extends Container {

  protected def startParts(): Unit

  // A def, because it reads the parts that startParts() created up to the failure.
  protected def stopActions: List[() => Unit]

  override final def start(): Unit = {
    try startParts()
    catch {
      case NonFatal(startFailure) =>
        try stop()
        catch {
          case NonFatal(stopFailure) => startFailure.addSuppressed(stopFailure)
        }
        throw startFailure
    }
  }

  override final def stop(): Unit = {
    StoppedOnFailedStart.throwFirst(stopActions.flatMap { stopAction =>
      try {
        stopAction()
        None
      } catch {
        case NonFatal(ex) => Some(ex)
      }
    })
  }

}

object StoppedOnFailedStart {

  // Throws the first failure, with the other failures attached as suppressed.
  private[containers] def throwFirst(failures: List[Throwable]): Unit = failures match {
    case Nil           => ()
    case first :: rest =>
      rest.foreach(first.addSuppressed)
      throw first
  }

}
