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
import org.testcontainers.images.RemoteDockerImage
import org.testcontainers.utility.{DockerImageName, ImageNameSubstitutor}
import tech.beshu.ror.utils.misc.OsUtils

/**
 * Warms the Docker image cache ONCE, in a single JVM, before the parallel integration-test workers
 * start: it pulls the base images the workers build their own images FROM, and builds (and stably
 * tags) the singleton ES+ROR image. Run by the `warmDockerImageCache` Gradle task as a
 * `test.dependsOn`.
 *
 * Why: at shardCount>=3 the worker JVMs otherwise do this work concurrently and it fails
 * intermittently — the singleton's build layers (plugin install + ror-tools patch) clash, and a base
 * image that no worker holds yet gets pulled by all of them at once, which ends as "unknown blob".
 * Warming the cache once means every worker gets a LAYER cache hit — so parallelism scales without
 * the concurrent-build failures.
 *
 * NOTE: the explicit `sys.exit` is REQUIRED, not stylistic. testcontainers/Ryuk leave non-daemon
 * threads alive; a graceful return (e.g. a monix TaskApp/IOApp) would NOT terminate the JVM, so the
 * gradle task would hang instead of exiting — observed as 120-min CI leg timeouts (build 10584) when
 * this was a TaskApp. `sys.exit` hard-kills regardless of lingering threads.
 */
object WarmDockerImageCache extends StrictLogging {

  def main(args: Array[String]): Unit = {
    logger.info("Warming the Docker image cache (once, before parallel test workers)...")
    try {
      pullBaseImages()
      prebuildSingletonEsImage()
      logger.info("Docker image cache warmed; the test workers can reuse the cached layers.")
      sys.exit(0)
    } catch {
      case ex: Throwable =>
        logger.error("Warming the Docker image cache failed", ex)
        sys.exit(1)
    }
  }

  // The images a worker's own `docker build` starts FROM. ImageFromDockerfile pre-fetches those with
  // substitution disabled, so this pull disables it too — otherwise a configured substitutor would
  // warm one name while the build pulls another.
  // Skipped on Windows, where ES runs as a native process and there is no Docker environment at all.
  private def pullBaseImages(): Unit = {
    val _ = OsUtils.ignoreOnWindows {
      List(WireMockContainer.BASE_IMAGE).foreach { image =>
        logger.info(s"Pulling base image $image ...")
        val _ = new RemoteDockerImage(DockerImageName.parse(image))
          .withImageNameSubstitutor(ImageNameSubstitutor.noop())
          .get()
      }
    }
  }

  // Starts the singleton container (the only reliable way to trigger the full image build+tag), then
  // stops it. The named image is reaped by Ryuk on this JVM's exit; the built LAYERS persist in the
  // Docker graph store, so each worker rebuilds the named image fast.
  private def prebuildSingletonEsImage(): Unit = {
    // Touching `singleton` triggers its construction + start(), which builds the image + its layers.
    val _ = SingletonEsContainerWithRorSecurity.singleton.nodes.head
    SingletonEsContainerWithRorSecurity.singleton.stop()
  }

}
