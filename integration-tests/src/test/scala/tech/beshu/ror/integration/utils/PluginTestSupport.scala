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
package tech.beshu.ror.integration.utils

import better.files.File
import com.typesafe.scalalogging.Logger
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.providers.*
import tech.beshu.ror.utils.misc.Resources.getResourcePath
import tech.beshu.ror.utils.misc.ScalaUtils.runWithTimeout

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.control.NonFatal

trait PluginTestSupport extends EsClusterProvider with CallingEsDirectly {
  this: MultipleEsTargets =>
}

trait SingletonPluginTestSupport
    extends PluginTestSupport
    with EsClusterProvider
    with BeforeAndAfterAll
    with ResolvedRorSettingsFileProvider {
  this: Suite & BaseSingleNodeEsClusterTest =>

  // Own logger (not a LazyLogging mixin): some suites already mix in LazyLogging, so a distinct name
  // avoids the `logger` override conflict.
  private val teardownLogger = Logger(classOf[SingletonPluginTestSupport])

  override lazy val targetEs: EsContainer = SingletonEsContainerWithRorSecurity.singleton.nodes.head

  private var startedDependencies = StartedClusterDependencies(Nil)

  override final def resolvedRorSettingsFile: File = {
    resolveSettings.toTry.get
  }

  override protected def beforeAll(): Unit = {
    // Claim exclusive ownership of the shared singleton BEFORE mutating it; fails loudly if another
    // suite still owns it (i.e. suites ever stop running serially within this JVM).
    SingletonEsContainerWithRorSecurity.acquire(this.getClass.getName)
    // ScalaTest skips afterAll if beforeAll throws, so any failure after acquire() must release the
    // latch here too — else the singleton stays owned and every later suite fails at acquire().
    try {
      startedDependencies = DependencyRunner.startDependencies(clusterDependencies)
      // Bound the blocking ES cleanup: if ES is wedged this used to hang the worker JVM at suite
      // start; on timeout we throw, failing THIS suite fast instead of hanging the leg for hours.
      runWithTimeout("cleanUpContainer", 2 minutes)(SingletonEsContainerWithRorSecurity.cleanUpContainer())
      SingletonEsContainerWithRorSecurity.updateSettings(resolvedRorSettingsFile.contentAsString)
      nodeDataInitializer.foreach(SingletonEsContainerWithRorSecurity.initNode)
      super.beforeAll()
    } catch {
      case NonFatal(e) =>
        SingletonEsContainerWithRorSecurity.release(this.getClass.getName)
        throw e
    }
  }

  override protected def afterAll(): Unit = {
    // release MUST run even if teardown throws -> try/finally, outermost; else the singleton stays
    // latched and every later suite fails at acquire(). Steps are time-bounded (ScalaUtils.runWithTimeout).
    try {
      try {
        runWithTimeout("afterAll", 3 minutes)(super.afterAll())
      } catch {
        case NonFatal(e) => teardownLogger.error("afterAll teardown failed/timed out — continuing cleanup", e)
      } finally {
        startedDependencies.values.foreach { started =>
          try runWithTimeout(s"stop-dependency-${started.name}", 1 minute)(started.container.stop())
          catch {
            case NonFatal(e) =>
              teardownLogger.error(s"Dependency '${started.name}' stop failed/timed out — continuing", e)
          }
        }
      }
    } finally {
      SingletonEsContainerWithRorSecurity.release(this.getClass.getName)
    }
  }

  private def resolveSettings: Either[Throwable, File] = {
    Either.cond(
      test = startedDependencies.values.size === clusterDependencies.size,
      right = resolvedSettings(startedDependencies),
      left = new IllegalStateException("Not all dependencies are started. Cannot read resolved settings yet")
    )
  }

  private def resolvedSettings(startedDependencies: StartedClusterDependencies) = {
    val settingsFile = File.apply(getResourcePath(rorSettingsFileName))
    RorSettingsAdjuster.adjustUsingDependencies(settingsFile, startedDependencies)
  }

}
