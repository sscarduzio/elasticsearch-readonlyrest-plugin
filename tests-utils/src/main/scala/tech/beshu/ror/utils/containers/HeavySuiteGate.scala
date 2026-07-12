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

import com.typesafe.scalalogging.LazyLogging

import java.io.File as JFile
import java.nio.channels.FileChannel
import java.nio.file.{Paths, StandardOpenOption}

/**
 * Machine-wide gate limiting how many "heavy" suites (multi-node / multi-cluster ES) run their
 * containers CONCURRENTLY across all sharded test JVMs on one host.
 *
 * Why: duration-balanced sharding keeps every shard fully loaded end-to-end, so several heavy
 * suites can boot 2-3 ES containers each at the same moment — the summed spike host-OOMed 16GB
 * CI runners 4/4 times. This gate converts that death into a short wait.
 *
 * How: N slot FILES under the root project dir, taken with OS file locks. Cross-process by
 * construction (the shard workers are separate JVMs on one machine), and self-healing: if a
 * worker crashes or is OOM-killed, its lock dies with the process and the slot frees itself.
 *
 * Deadlock-safe by design: ONE permit per SUITE, acquired before any of its clusters start and
 * held until all are stopped — there is no hold-and-wait on a second permit.
 *
 * Opt-in: enabled only when ROR_HEAVY_SUITE_PERMITS is set (CI); local runs are unaffected.
 */
object HeavySuiteGate extends LazyLogging {

  final class Slot private[HeavySuiteGate] (channel: FileChannel, lock: java.nio.channels.FileLock) {

    def release(): Unit = {
      try lock.release()
      finally channel.close()
    }

  }

  private val permits: Option[Int] =
    sys.env.get("ROR_HEAVY_SUITE_PERMITS").flatMap(_.toIntOption).filter(_ > 0)

  private def slotDir: JFile =
    Option(System.getProperty("project.dir"))
      .map(new JFile(_))
      .getOrElse(new JFile(System.getProperty("java.io.tmpdir")))

  /** Blocks until a slot is free. Returns None when the gate is disabled (env not set). */
  def acquire(suiteName: String): Option[Slot] = permits.map { n =>
    val start = System.currentTimeMillis()
    var attempt = 0
    var acquired: Slot = null
    while (acquired == null) {
      var i = 0
      while (acquired == null && i < n) {
        val channel = FileChannel.open(
          Paths.get(slotDir.getAbsolutePath, s".ror-heavy-suite-slot-$i.lock"),
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE
        )
        val lock = channel.tryLock()
        if (lock != null) {
          val waited = (System.currentTimeMillis() - start) / 1000
          logger.info(s"[$suiteName] heavy-suite slot $i/$n acquired after ${waited}s")
          acquired = new Slot(channel, lock)
        } else {
          channel.close()
        }
        i += 1
      }
      if (acquired == null) {
        attempt += 1
        if (attempt % 20 == 0) {
          logger.info(
            s"[$suiteName] still waiting for a heavy-suite slot (${(System.currentTimeMillis() - start) / 1000}s)"
          )
        }
        Thread.sleep(3000)
      }
    }
    acquired
  }

}
