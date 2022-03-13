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
package tech.beshu.ror.integration.suites.base

import better.files.File
import org.scalatest.BeforeAndAfterAll
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{DependencyDef, DependencyRunner, RorConfigAdjuster, StartedClusterDependencies}
import tech.beshu.ror.utils.misc.Resources.getResourcePath

trait BaseTestConfigSuite
  extends BeforeAndAfterAll {
  this: BaseSingleNodeEsClusterTest =>

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    TestDependencyRunner.start()
  }

  override protected def afterAll(): Unit = {
    TestDependencyRunner.stop()
    super.afterAll()
  }

  protected def mode: RorConfigAdjuster.Mode

  protected final def testEngineConfig(): String = {
    TestDependencyRunner.resolvedConfig(mode) match {
      case Right(config) => config
      case Left(error) => throw new IllegalStateException(error)
    }
  }

  protected def testDependencies: List[DependencyDef] = List.empty

  private object TestDependencyRunner {
    private var startedDependencies = StartedClusterDependencies(Nil)

    def start(): Unit = {
      startedDependencies = DependencyRunner.startDependencies(testDependencies)
    }

    def stop(): Unit = {
      startedDependencies.values.foreach(started => started.container.stop())
    }

    def resolvedConfig(mode: RorConfigAdjuster.Mode): Either[String, String] = {
      Either.cond(
        test = startedDependencies.values.size === testDependencies.size,
        right = resolveTestConfig(startedDependencies, mode),
        left = "Not all dependencies are started. Cannot read resolved config"
      )
    }

    private def resolveTestConfig(startedDependencies: StartedClusterDependencies, mode: RorConfigAdjuster.Mode) = {
      val configFile = File.apply(getResourcePath(rorConfigFileName))
      RorConfigAdjuster.adjustUsingDependencies(configFile, startedDependencies, mode).contentAsString
    }
  }
}
