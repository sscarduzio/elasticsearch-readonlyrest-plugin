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
import tech.beshu.ror.utils.containers.SecurityType.RorWithXpackSecurity
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin
import tech.beshu.ror.utils.elasticsearch.{IndexManager, LegacyTemplateManager, RorApiManager, SnapshotManager}
import tech.beshu.ror.utils.misc.EsModulePatterns

import java.util.concurrent.atomic.AtomicReference
import scala.util.{Failure, Success, Try}

object SingletonEsContainerWithRorSecurity
  extends EsClusterProvider
    with EsContainerCreator
    with StrictLogging
    with EsModulePatterns {

  val singleton: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings.create(
      clusterName = "ROR_SINGLE",
      securityType = RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default)
    )
  )

  private lazy val adminClient = singleton.nodes.head.adminClient

  private lazy val indexManager = new IndexManager(adminClient, singleton.esVersion)
  private lazy val templateManager = new LegacyTemplateManager(adminClient, singleton.esVersion)
  private lazy val snapshotManager = new SnapshotManager(adminClient, singleton.esVersion)
  private lazy val rorApiManager = new RorApiManager(adminClient, singleton.esVersion)

  logger.info("Starting singleton es container...")
  singleton.start()

  // --- Non-interference guard (parallel-safety by construction) ----------------------------------
  // This singleton is ONE mutable ES shared by ~all single-node suites *within a worker JVM*. The
  // model is only safe because suites run STRICTLY SERIALLY inside a JVM (the scalatest-junit engine
  // is a non-hierarchical, single-threaded TestEngine; suites carry no ParallelTestExecution), while
  // parallelism happens ACROSS Gradle worker JVMs — each a separate process with its OWN singleton.
  // This latch turns that invariant from "remember not to break it" into "the build fails loudly if
  // it's ever broken" (e.g. a future parallel engine, or a suite that mutates the singleton without
  // going through beforeAll/afterAll). It does NOT add locking/coordination — concurrent ownership is
  // a test-harness bug to surface, not a race to serialize.
  private val currentOwner = new AtomicReference[String](null)

  def acquire(owner: String): Unit = {
    if (!currentOwner.compareAndSet(null, owner)) {
      throw new IllegalStateException(
        s"Singleton ES non-interference violated: '$owner' tried to use the shared singleton while " +
          s"'${currentOwner.get()}' still owns it. Suites sharing this singleton MUST run serially " +
          s"within a JVM (parallelism is across worker JVMs only). A concurrent owner means the test " +
          s"engine started running suites in parallel, or a suite mutated the singleton outside " +
          s"beforeAll/afterAll — fix that rather than adding locking."
      )
    }
  }

  def release(owner: String): Unit = {
    // Only the owner clears it; mismatches are logged (afterAll must not mask the real test failure).
    if (!currentOwner.compareAndSet(owner, null)) {
      logger.warn(s"Singleton ES release by '$owner' but current owner is '${currentOwner.get()}' — " +
        s"ignoring (possible missed acquire/double release).")
    }
  }

  def cleanUpContainer(): Unit = {
    logOnFailure(indexManager.removeAllIndices().force())
    logOnFailure(templateManager.deleteAllTemplates().force())
    logOnFailure(snapshotManager.deleteAllRepositories().force())
  }

  def updateSettings(rorSettings: String): Unit = {
    rorApiManager
      .updateRorInIndexSettings(rorSettings)
      .forceOKStatusOrSettingsAlreadyLoaded()
  }

  def initNode(nodeDataInitializer: ElasticsearchNodeDataInitializer): Unit = {
    nodeDataInitializer.initialize(singleton.esVersion, adminClient)
  }

  private def logOnFailure[A](action: => A): Unit = {
    Try(action) match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Error occurred while cleanup of singleton ES container: $ex")
    }
  }
}