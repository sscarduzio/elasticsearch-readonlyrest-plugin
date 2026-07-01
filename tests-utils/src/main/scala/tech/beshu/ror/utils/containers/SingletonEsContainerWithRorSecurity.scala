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

  // Ensures only one suite uses the shared singleton ES at a time within this worker JVM. Ownership
  // is a VALUE: acquire() returns the sole token, every mutating op requires it and checks it is
  // still current — so a missed acquire or a stale owner fails at use time, not by convention.
  final class Ownership private[containers] (private[containers] val owner: String)

  private val currentOwner = new AtomicReference[Ownership](null)

  def acquire(owner: String): Ownership = {
    val ownership = new Ownership(owner)
    if (!currentOwner.compareAndSet(null, ownership)) {
      throw new IllegalStateException(
        s"Singleton ES is already in use by suite '${currentOwner.get().owner}' — '$owner' cannot use " +
          s"it concurrently. Suites sharing this singleton must run one at a time within a JVM."
      )
    }
    ownership
  }

  def release(ownership: Ownership): Unit = {
    // Only the current owner clears it; mismatches are logged (afterAll must not mask the real failure).
    if (!currentOwner.compareAndSet(ownership, null)) {
      logger.warn(
        s"Singleton ES release by '${ownership.owner}' but current owner is " +
          s"'${Option(currentOwner.get()).map(_.owner).getOrElse("<none>")}' — ignoring."
      )
    }
  }

  def cleanUpContainer(ownership: Ownership): Unit = {
    requireCurrent(ownership)
    logOnFailure(indexManager.removeAllIndices().force())
    logOnFailure(templateManager.deleteAllTemplates().force())
    logOnFailure(snapshotManager.deleteAllRepositories().force())
  }

  def updateSettings(rorSettings: String, ownership: Ownership): Unit = {
    requireCurrent(ownership)
    rorApiManager
      .updateRorInIndexSettings(rorSettings)
      .forceOKStatusOrSettingsAlreadyLoaded()
  }

  def initNode(nodeDataInitializer: ElasticsearchNodeDataInitializer, ownership: Ownership): Unit = {
    requireCurrent(ownership)
    nodeDataInitializer.initialize(singleton.esVersion, adminClient)
  }

  private def requireCurrent(ownership: Ownership): Unit = {
    if (currentOwner.get() ne ownership) {
      throw new IllegalStateException(
        s"Singleton ES mutation by '${ownership.owner}' without current ownership (current: " +
          s"'${Option(currentOwner.get()).map(_.owner).getOrElse("<none>")}') — acquire() it first."
      )
    }
  }

  private def logOnFailure[A](action: => A): Unit = {
    Try(action) match {
      case Success(_)  => ()
      case Failure(ex) =>
        logger.error(s"Error occurred while cleanup of singleton ES container: $ex")
    }
  }

}
