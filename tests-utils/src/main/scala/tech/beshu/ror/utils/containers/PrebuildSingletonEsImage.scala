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

import com.typesafe.scalalogging.StrictLogging

/**
 * Builds (and stably tags) the singleton ES+ROR image ONCE, in a single JVM, before the parallel
 * integration-test workers start. Run by the `prebuildEsImage` Gradle task as a `test.dependsOn`.
 *
 * Why: at shardCount>=3 the worker JVMs otherwise build this identical image concurrently, and the
 * Docker build layers (plugin install + ror-tools patch) fail intermittently. Pre-building once means
 * every worker gets a Docker LAYER cache hit and never rebuilds — so parallelism scales without the
 * concurrent-build failures.
 *
 * Starts the singleton container (the only reliable way to trigger the full image build+tag), then
 * stops it. The named image is reaped by Ryuk on this JVM's exit; the built LAYERS persist in the
 * Docker graph store, so each worker rebuilds the named image fast.
 *
 * NOTE: the explicit `sys.exit` is REQUIRED, not stylistic. testcontainers/Ryuk leave non-daemon
 * threads alive; a graceful return (e.g. a monix TaskApp/IOApp) would NOT terminate the JVM, so the
 * `prebuildEsImage` gradle task would hang instead of exiting — observed as 120-min CI leg timeouts
 * (build 10584) when this was a TaskApp. `sys.exit` hard-kills regardless of lingering threads.
 */
object PrebuildSingletonEsImage extends StrictLogging {

  def main(args: Array[String]): Unit = {
    logger.info("Pre-building the singleton ES image (once, before parallel test workers)...")
    try {
      // Touching `singleton` triggers its construction + start(), which builds the image + its layers.
      val _ = SingletonEsContainerWithRorSecurity.singleton.nodes.head
      logger.info("Singleton ES image pre-build complete; layers cached for the test workers to rebuild from.")
      SingletonEsContainerWithRorSecurity.singleton.stop()
      sys.exit(0)
    } catch {
      case ex: Throwable =>
        logger.error("Pre-build of the singleton ES image failed", ex)
        sys.exit(1)
    }
  }

}
