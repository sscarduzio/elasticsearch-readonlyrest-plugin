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

// When startParts() fails, start() stops the started parts, because ForAllTestContainer calls stop()
// only after a successful start().
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
    StoppedOnFailedStart
      .firstWithOthersSuppressed(stopActions.flatMap { stopAction =>
        try {
          stopAction()
          None
        } catch {
          case NonFatal(ex) => Some(ex)
        }
      })
      .foreach(failure => throw failure)
  }

}

object StoppedOnFailedStart {

  private[containers] def firstWithOthersSuppressed(failures: List[Throwable]): Option[Throwable] =
    failures match {
      case Nil           => None
      case first :: rest =>
        rest.foreach(first.addSuppressed)
        Some(first)
    }

}
