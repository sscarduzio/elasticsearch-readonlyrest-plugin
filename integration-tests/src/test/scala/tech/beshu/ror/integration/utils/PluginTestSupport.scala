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

  // Own logger (not a LazyLogging mixin) — some concrete suites already mix in LazyLogging, and a
  // second `logger` from the trait would clash. Distinct name avoids the override conflict.
  private val teardownLogger = Logger(classOf[SingletonPluginTestSupport])

  override lazy val targetEs: EsContainer = SingletonEsContainerWithRorSecurity.singleton.nodes.head

  private var startedDependencies = StartedClusterDependencies(Nil)

  override final def resolvedRorSettingsFile: File = {
    resolveSettings.toTry.get
  }

  override protected def beforeAll(): Unit = {
    // Claim exclusive ownership of the shared singleton BEFORE mutating it (cleanUp/updateSettings/
    // init). Fails loudly if another suite still owns it — i.e. if suites ever stop running serially
    // within this JVM (see SingletonEsContainerWithRorSecurity.acquire).
    SingletonEsContainerWithRorSecurity.acquire(this.getClass.getName)
    // ScalaTest does NOT call afterAll if beforeAll throws, so any failure AFTER acquire() must
    // release the latch here — otherwise the singleton stays owned and every later suite on this
    // worker fails at acquire() with a misleading "non-interference" error. Symmetric with afterAll.
    try {
      startedDependencies = DependencyRunner.startDependencies(clusterDependencies)
      // Bound the blocking ES cleanup (removeAllIndices/deleteAllTemplates/deleteAllRepositories
      // .force()) — if ES is wedged, this used to hang the worker JVM at suite start forever. On
      // timeout we throw, failing THIS suite fast rather than hanging the whole leg for hours.
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
    // release MUST run even if test teardown or dependency shutdown throws — otherwise the singleton
    // stays latched and every subsequent suite on this worker fails at acquire(). Keep it outermost.
    //
    // Each blocking teardown step is time-bounded: a WEDGED (not dead) ES or dependency container
    // used to hang the worker JVM forever here, producing 300+ minute "stopped hearing from agent"
    // legs. With a bound, a stuck step is abandoned and we move on, so the leg self-terminates and
    // the singleton latch is always released. (See ScalaUtils.runWithTimeout.)
    try {
      try {
        runWithTimeout("afterAll", 3 minutes)(super.afterAll())
      } catch {
        case NonFatal(e) => teardownLogger.error("afterAll teardown failed/timed out — continuing cleanup", e)
      } finally {
        startedDependencies.values.foreach { started =>
          try runWithTimeout(s"stop-dependency-${started.name}", 1 minute)(started.container.stop())
          catch { case NonFatal(e) => teardownLogger.error(s"Dependency '${started.name}' stop failed/timed out — continuing", e) }
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