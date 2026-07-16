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

import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.{Path, StandardOpenOption}

/**
 * Cross-process mutex helpers backed by OS file locks (blocking; for the N-slot
 * polling variant see [[FileLockSemaphore]]). Self-healing: a crashed holder's
 * lock dies with its process.
 */
object FileLocks {

  /** Runs `body` while holding an exclusive OS lock on `lockFile` (created if absent). */
  def withExclusiveLock[A](lockFile: Path)(body: => A): A = {
    val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    // lock() inside the try: if it throws, the finally still closes the channel (no fd leak)
    var lock: FileLock = null
    try {
      lock = channel.lock()
      body
    } finally {
      try if (lock != null) lock.release()
      finally channel.close()
    }
  }

}
