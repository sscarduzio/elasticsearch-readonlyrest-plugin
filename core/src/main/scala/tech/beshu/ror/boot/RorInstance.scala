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
package tech.beshu.ror.boot

import cats.effect.Resource
import cats.implicits.toShow
import cats.syntax.either._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.api.{AuthMockApi, ConfigApi}
import tech.beshu.ror.boot.engines.{Engines, ImpersonatorsReloadableEngine, MainReloadableEngine}
import tech.beshu.ror.configuration.IndexConfigManager.SavingIndexConfigError
import tech.beshu.ror.configuration.RorProperties.RefreshInterval
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.FileConfigLoader
import tech.beshu.ror.configuration.{IndexConfigManager, RawRorConfig, RorProperties}
import tech.beshu.ror.providers.{JavaUuidProvider, PropertiesProvider}

import scala.concurrent.duration.FiniteDuration

class RorInstance private(boot: ReadonlyRest,
                          mode: RorInstance.Mode,
                          initialEngine: (ReadonlyRest.Engine, RawRorConfig),
                          reloadInProgress: Semaphore[Task],
                          rorConfigurationIndex: RorConfigurationIndex)
                         (implicit propertiesProvider: PropertiesProvider,
                          scheduler: Scheduler)
  extends Logging {

  import RorInstance.ScheduledReloadError.{EngineReloadError, ReloadingInProgress}
  import RorInstance._

  logger.info("ReadonlyREST core was loaded ...")
  mode match {
    case Mode.WithPeriodicIndexCheck =>
      RorProperties.rorIndexSettingReloadInterval match {
        case RefreshInterval.Disabled =>
          logger.info(s"[CLUSTERWIDE SETTINGS] Scheduling in-index settings check disabled")
        case RefreshInterval.Enabled(interval) =>
          scheduleIndexConfigChecking(interval)
      }
    case Mode.NoPeriodicIndexCheck => Cancelable.empty
  }

  private val aMainEngine = new MainReloadableEngine(
    boot,
    initialEngine,
    reloadInProgress,
    rorConfigurationIndex,
  )
  private val anImpersonatorsEngine = new ImpersonatorsReloadableEngine(
    boot,
    reloadInProgress,
    rorConfigurationIndex
  )

  private val configRestApi = new ConfigApi(
    rorInstance = this,
    boot.indexConfigManager,
    new FileConfigLoader(boot.esConfigPath),
    rorConfigurationIndex
  )

  private val authMockRestApi = new AuthMockApi(boot.mocksProvider)

  def engines: Option[Engines] = aMainEngine.engine.map(Engines(_, anImpersonatorsEngine.engine))

  def configApi: ConfigApi = configRestApi

  def authMockApi: AuthMockApi = authMockRestApi

  def forceReloadFromIndex()
                          (implicit requestId: RequestId): Task[Either[IndexConfigReloadError, Unit]] =
    aMainEngine.forceReloadFromIndex()

  def forceReloadAndSave(config: RawRorConfig)
                        (implicit requestId: RequestId): Task[Either[IndexConfigReloadWithUpdateError, Unit]] =
    aMainEngine.forceReloadAndSave(config)

  def forceReloadImpersonatorsEngine(config: RawRorConfig,
                                     ttl: FiniteDuration)
                                    (implicit requestId: RequestId): Task[Either[RawConfigReloadError, Unit]] = {
    anImpersonatorsEngine.forceReloadImpersonatorsEngine(config, ttl)
  }

  def invalidateImpersonationEngine()
                                   (implicit requestId: RequestId): Task[Unit] = {
    anImpersonatorsEngine.invalidateImpersonationEngine()
  }

  def stop(): Task[Unit] = {
    implicit val requestId: RequestId = RequestId("ES sigterm")
    for {
      _ <- anImpersonatorsEngine.stop()
      _ <- aMainEngine.stop()
    } yield ()
  }

  private def scheduleIndexConfigChecking(interval: FiniteDuration Refined Positive): Cancelable = {
    logger.debug(s"[CLUSTERWIDE SETTINGS] Scheduling next in-index settings check within $interval")
    scheduler.scheduleOnce(interval.value) {
      implicit val requestId: RequestId = RequestId(JavaUuidProvider.random.toString)
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Loading ReadonlyREST config from index ...")
      tryEngineReload()
        .runAsync {
          case Right(Right(_)) =>
            scheduleIndexConfigChecking(interval)
          case Right(Left(ReloadingInProgress)) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Reloading in progress ... skipping")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.ConfigUpToDate(_))))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Settings are up to date. Nothing to reload.")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.RorInstanceStopped)))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Stopping periodic settings check - application is being stopped")
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.ReloadingFailed(startingFailure))))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] ReadonlyREST starting failed: ${startingFailure.message}")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.LoadingConfigError(error)))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Loading config from index failed: ${error.show}")
            scheduleIndexConfigChecking(interval)
          case Left(ex) =>
            logger.error(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Checking index settings failed: error", ex)
            scheduleIndexConfigChecking(interval)
        }
    }
  }

  private def tryEngineReload()
                             (implicit requestId: RequestId) = {
    val criticalSection = Resource.make(reloadInProgress.tryAcquire) {
      case true =>
        reloadInProgress.release
      case false =>
        Task.unit
    }
    criticalSection.use {
      case true =>
        aMainEngine
          .reloadEngineUsingIndexConfigWithoutPermit()
          .map(_.leftMap(ScheduledReloadError.EngineReloadError.apply))
      case false =>
        Task.now(Left(ScheduledReloadError.ReloadingInProgress))
    }
  }

}

object RorInstance {

  sealed trait RawConfigReloadError
  object RawConfigReloadError {
    final case class ReloadingFailed(failure: ReadonlyRest.StartingFailure) extends RawConfigReloadError
    final case class ConfigUpToDate(config: RawRorConfig) extends RawConfigReloadError
    object RorInstanceStopped extends RawConfigReloadError
  }

  sealed trait IndexConfigReloadWithUpdateError
  object IndexConfigReloadWithUpdateError {
    final case class ReloadError(undefined: RawConfigReloadError) extends IndexConfigReloadWithUpdateError
    final case class IndexConfigSavingError(underlying: SavingIndexConfigError) extends IndexConfigReloadWithUpdateError
  }

  sealed trait IndexConfigReloadError
  object IndexConfigReloadError {
    final case class LoadingConfigError(underlying: ConfigLoaderError[IndexConfigManager.IndexConfigError]) extends IndexConfigReloadError
    final case class ReloadError(underlying: RawConfigReloadError) extends IndexConfigReloadError
  }

  private sealed trait ScheduledReloadError
  private object ScheduledReloadError {
    case object ReloadingInProgress extends ScheduledReloadError
    final case class EngineReloadError(underlying: IndexConfigReloadError) extends ScheduledReloadError
  }

  def createWithPeriodicIndexCheck(boot: ReadonlyRest,
                                   engine: ReadonlyRest.Engine,
                                   config: RawRorConfig,
                                   rorConfigurationIndex: RorConfigurationIndex)
                                  (implicit propertiesProvider: PropertiesProvider,
                                   scheduler: Scheduler): Task[RorInstance] = {
    create(boot, Mode.WithPeriodicIndexCheck, engine, config, rorConfigurationIndex)
  }

  def createWithoutPeriodicIndexCheck(boot: ReadonlyRest,
                                      engine: ReadonlyRest.Engine,
                                      config: RawRorConfig,
                                      rorConfigurationIndex: RorConfigurationIndex)
                                     (implicit propertiesProvider: PropertiesProvider,
                                      scheduler: Scheduler): Task[RorInstance] = {
    create(boot, Mode.NoPeriodicIndexCheck, engine, config, rorConfigurationIndex)
  }

  private def create(boot: ReadonlyRest,
                     mode: RorInstance.Mode,
                     engine: ReadonlyRest.Engine,
                     config: RawRorConfig,
                     rorConfigurationIndex: RorConfigurationIndex)
                    (implicit propertiesProvider: PropertiesProvider,
                     scheduler: Scheduler) = {
    Semaphore[Task](1)
      .map { isReloadInProgressSemaphore =>
        new RorInstance(
          boot,
          mode,
          (engine, config),
          isReloadInProgressSemaphore,
          rorConfigurationIndex
        )
      }
  }

  private sealed trait Mode
  private object Mode {
    case object WithPeriodicIndexCheck extends Mode
    case object NoPeriodicIndexCheck extends Mode
  }
}

