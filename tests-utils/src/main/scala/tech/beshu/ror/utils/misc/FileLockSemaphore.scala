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
package tech.beshu.ror.utils.misc

import com.typesafe.scalalogging.LazyLogging

import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.{Path, StandardOpenOption}
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try, Using}

/**
 * Cross-process counting semaphore backed by OS file locks: N slot files under `slotDir`,
 * each held via [[FileChannel.tryLock]]. Works across separate JVMs on one machine by
 * construction, and is self-healing — if a holder crashes or is OOM-killed, its lock dies
 * with the process and the slot frees itself.
 *
 * Polling is inherent to the N-slot shape: a process cannot OS-block on "any of N files"
 * at once, so waiters retry [[FileChannel.tryLock]] over the slots. For a single named
 * mutex use [[FileLocks.withExclusiveLock]] instead — its blocking `lock()` wakes the
 * waiter immediately, with no poll latency.
 *
 * A permit is a [[FileLockSemaphore.Slot]]; release it in a `finally`.
 */
final class FileLockSemaphore(permits: Int, slotDir: Path, slotFilePrefix: String) extends LazyLogging {

  require(permits > 0, s"permits must be positive, got $permits")

  import FileLockSemaphore.Slot

  /** Blocks until a slot is free. `label` is only a log tag. */
  def acquire(label: String): Slot = {
    val start = System.currentTimeMillis()

    @tailrec
    def loop(attempt: Int): Slot = {
      tryAcquireAnySlot() match {
        case Some(slot) =>
          logger.info(s"[$label] slot acquired after ${elapsedSeconds(start)}s")
          slot
        case scala.None =>
          if (attempt % 20 == 0) {
            logger.info(s"[$label] still waiting for a free slot (${elapsedSeconds(start)}s)")
          }
          Thread.sleep(3000)
          loop(attempt + 1)
      }
    }

    loop(attempt = 1)
  }

  private def tryAcquireAnySlot(): Option[Slot] = {
    (0 until permits).iterator
      .map(tryAcquireSlot)
      .collectFirst { case Some(slot) => slot }
  }

  private def tryAcquireSlot(idx: Int): Option[Slot] = {
    val channel = FileChannel.open(
      slotDir.resolve(s"$slotFilePrefix-$idx.lock"),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE
    )
    Try(Option(channel.tryLock())) match {
      case Success(Some(lock)) =>
        Some(new Slot(channel, lock))
      case Success(scala.None) =>
        channel.close()
        scala.None
      case Failure(ex) =>
        channel.close()
        throw ex
    }
  }

  private def elapsedSeconds(start: Long) = (System.currentTimeMillis() - start) / 1000
}

object FileLockSemaphore {

  final class Slot private[FileLockSemaphore] (channel: FileChannel, lock: FileLock) {

    def release(): Unit = {
      Using.resource(channel) { _ =>
        lock.release()
      }
    }

  }

}
