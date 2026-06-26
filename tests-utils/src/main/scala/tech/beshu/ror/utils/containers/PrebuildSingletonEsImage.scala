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

import cats.effect.ExitCode
import com.typesafe.scalalogging.StrictLogging
import monix.eval.{Task, TaskApp}

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
 * stops it. The named image is reaped by Ryuk on this JVM's exit (testcontainers labels every
 * ImageFromDockerfile build with DEFAULT_LABELS, which Ryuk reaps regardless of deleteOnExit); the
 * built LAYERS persist in the Docker graph store, so each worker rebuilds the named image fast.
 */
object PrebuildSingletonEsImage extends TaskApp with StrictLogging {

  override def run(args: List[String]): Task[ExitCode] =
    (for {
      _ <- Task.delay(logger.info("Pre-building the singleton ES image (once, before parallel test workers)..."))
      // Touching `singleton` triggers its construction + start(), which builds the image + its layers.
      _ <- Task.delay(SingletonEsContainerWithRorSecurity.singleton.nodes.head)
      _ <- Task.delay(
        logger.info("Singleton ES image pre-build complete; layers cached for the test workers to rebuild from.")
      )
      // Stop the container so the pre-build JVM doesn't hold an ES instance. Ryuk reaps the named image
      // on this JVM's exit; the LAYERS persist, so workers rebuild the named image fast (cache hit).
      _ <- Task.delay(SingletonEsContainerWithRorSecurity.singleton.stop())
    } yield ExitCode.Success)
      .onErrorHandle { ex =>
        logger.error("Pre-build of the singleton ES image failed", ex)
        ExitCode.Error
      }

}
