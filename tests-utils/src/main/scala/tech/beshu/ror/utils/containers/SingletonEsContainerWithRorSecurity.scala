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
  final class Ownership private[containers] (private[containers] val owner: String) {

    def cleanUpContainer(): Unit = {
      requireCurrent()
      logOnFailure(indexManager.removeAllIndices().force())
      logOnFailure(templateManager.deleteAllTemplates().force())
      logOnFailure(snapshotManager.deleteAllRepositories().force())
    }

    def updateSettings(rorSettings: String): Unit = {
      requireCurrent()
      rorApiManager
        .updateRorInIndexSettings(rorSettings)
        .forceOKStatusOrSettingsAlreadyLoaded()
    }

    def initNode(nodeDataInitializer: ElasticsearchNodeDataInitializer): Unit = {
      requireCurrent()
      nodeDataInitializer.initialize(singleton.esVersion, adminClient)
    }

    def release(): Unit = {
      // Only the current owner clears it; mismatches are logged (afterAll must not mask the real failure).
      // CAS against the observed Option — atomic, so a racing acquire() can't sneak in between read and write.
      val seen = currentOwner.get()
      seen match {
        case Some(o) if o eq this =>
          if (!currentOwner.compareAndSet(seen, None))
            logger.warn(
              s"Singleton ES release by '${this.owner}' lost the race — current owner is " +
                s"'${currentOwner.get().map(_.owner).getOrElse("<none>")}' — ignoring."
            )
        case _ =>
          logger.warn(
            s"Singleton ES release by '${this.owner}' but current owner is " +
              s"'${currentOwner.get().map(_.owner).getOrElse("<none>")}' — ignoring."
          )
      }
    }

    private def requireCurrent(): Unit = {
      currentOwner.get() match {
        case Some(o) if o eq this => () // ok
        case other =>
          throw new IllegalStateException(
            s"Singleton ES mutation by '${this.owner}' without current ownership (current: " +
              s"'${other.map(_.owner).getOrElse("<none>")}') — acquire() it first."
          )
      }
    }
  }

  private val currentOwner = new AtomicReference[Option[Ownership]](None)

  def acquire(owner: String): Ownership = {
    val ownership = new Ownership(owner)
    if (!currentOwner.compareAndSet(None, Some(ownership))) {
      throw new IllegalStateException(
        s"Singleton ES is already in use by suite '${currentOwner.get().map(_.owner).getOrElse("<none>")}' — '$owner' cannot use " +
          s"it concurrently. Suites sharing this singleton must run one at a time within a JVM."
      )
    }
    ownership
  }

  private def logOnFailure[A](action: => A): Unit = {
    Try(action) match {
      case Success(_)  => ()
      case Failure(ex) =>
        logger.error(s"Error occurred while cleanup of singleton ES container: $ex")
    }
  }

}
