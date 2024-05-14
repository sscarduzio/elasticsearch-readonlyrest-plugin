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

import cats.Show
import cats.effect.Resource
import cats.implicits.toShow
import cats.syntax.either._
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.mocks.{AuthServicesMocks, MocksProvider}
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.api.{AuthMockApi, ConfigApi, TestConfigApi}
import tech.beshu.ror.boot.engines.{Engines, MainConfigBasedReloadableEngine, TestConfigBasedReloadableEngine}
import tech.beshu.ror.configuration.RorProperties.RefreshInterval
import tech.beshu.ror.configuration.index.{IndexConfigError, SavingIndexConfigError}
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.FileConfigLoader
import tech.beshu.ror.configuration.loader.toDomain
import tech.beshu.ror.configuration.{EnvironmentConfig, RawRorConfig, RorConfig, RorProperties}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.time.Instant

class RorInstance private(boot: ReadonlyRest,
                          mode: RorInstance.Mode,
                          initialEngine: ReadonlyRest.MainEngine,
                          mainReloadInProgress: Semaphore[Task],
                          initialTestEngine: ReadonlyRest.TestEngine,
                          testReloadInProgress: Semaphore[Task],
                          rorConfigurationIndex: RorConfigurationIndex)
                         (implicit environmentConfig: EnvironmentConfig,
                          scheduler: Scheduler)
  extends Logging {

  import RorInstance.ScheduledReloadError.{EngineReloadError, ReloadingInProgress}
  import RorInstance._

  logger.info("ReadonlyREST was loaded ...")
  private val configsReloadTask = mode match {
    case Mode.WithPeriodicIndexCheck =>
      RorProperties.rorIndexSettingReloadInterval(environmentConfig.propertiesProvider) match {
        case RefreshInterval.Disabled =>
          logger.info(s"[CLUSTERWIDE SETTINGS] Scheduling in-index settings check disabled")
          Cancelable.empty
        case RefreshInterval.Enabled(interval) =>
          scheduleEnginesReload(interval)
      }
    case Mode.NoPeriodicIndexCheck =>
      logger.info(s"[CLUSTERWIDE SETTINGS] Scheduling in-index settings check disabled")
      Cancelable.empty
  }

  private val aMainConfigEngine = new MainConfigBasedReloadableEngine(
    boot,
    (initialEngine.engine, initialEngine.config),
    mainReloadInProgress,
    rorConfigurationIndex,
  )
  private val anTestConfigEngine = TestConfigBasedReloadableEngine.create(
    boot,
    initialTestEngine,
    testReloadInProgress,
    rorConfigurationIndex
  )

  private val configRestApi = new ConfigApi(
    rorInstance = this,
    boot.indexConfigManager,
    new FileConfigLoader(boot.esEnv.configPath),
    rorConfigurationIndex
  )

  private val authMockRestApi = new AuthMockApi(
    rorInstance = this
  )

  private val testConfigRestApi = new TestConfigApi(this)

  def engines: Option[Engines] = aMainConfigEngine.engine.map(Engines(_, anTestConfigEngine.engine))

  def configApi: ConfigApi = configRestApi

  def authMockApi: AuthMockApi = authMockRestApi

  def testConfigApi: TestConfigApi = testConfigRestApi

  def mocksProvider: MocksProvider = boot.authServicesMocksProvider

  def forceReloadFromIndex()
                          (implicit requestId: RequestId): Task[Either[IndexConfigReloadError, Unit]] =
    aMainConfigEngine.forceReloadFromIndex()

  def forceReloadAndSave(config: RawRorConfig)
                        (implicit requestId: RequestId): Task[Either[IndexConfigReloadWithUpdateError, Unit]] =
    aMainConfigEngine.forceReloadAndSave(config)

  def currentTestConfig()
                       (implicit requestId: RequestId): Task[TestConfig] = {
    anTestConfigEngine.currentTestConfig()
  }

  def forceReloadTestConfigEngine(config: RawRorConfig,
                                  ttl: PositiveFiniteDuration)
                                 (implicit requestId: RequestId): Task[Either[IndexConfigReloadWithUpdateError, TestConfig.Present]] = {
    anTestConfigEngine.forceReloadTestConfigEngine(config, ttl)
  }

  def invalidateTestConfigEngine()
                                (implicit requestId: RequestId): Task[Either[IndexConfigInvalidationError, Unit]] = {
    anTestConfigEngine.invalidateTestConfigEngine()
  }

  def updateAuthMocks(mocks: AuthServicesMocks)
                     (implicit requestId: RequestId): Task[Either[IndexConfigUpdateError, Unit]] = {
    anTestConfigEngine.saveConfig(mocks)
  }

  def stop(): Task[Unit] = {
    implicit val requestId: RequestId = RequestId("ES sigterm")
    for {
      _ <- Task.delay(configsReloadTask.cancel())
      _ <- anTestConfigEngine.stop()
      _ <- aMainConfigEngine.stop()
    } yield ()
  }

  private def scheduleEnginesReload(interval: PositiveFiniteDuration): Cancelable = {
    val reloadTask = { (requestId: RequestId) =>
      Task.sequence {
        Seq(
          tryMainEngineReload(requestId).map(result => (ConfigType.Main, result)),
          tryTestEngineReload(requestId).map(result => (ConfigType.Test, result))
        )
      }
    }
    scheduleIndexConfigChecking(interval, reloadTask)
  }

  private def scheduleIndexConfigChecking(interval: PositiveFiniteDuration,
                                          reloadTask: RequestId => Task[Seq[(ConfigType, Either[ScheduledReloadError, Unit])]]): Cancelable = {
    logger.debug(s"[CLUSTERWIDE SETTINGS] Scheduling next in-index settings check within ${interval.value}")
    scheduler.scheduleOnce(interval.value) {
      implicit val requestId: RequestId = RequestId(environmentConfig.uuidProvider.random.toString)
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Loading ReadonlyREST configs from index ...")
      reloadTask(requestId)
        .runAsync {
          case Right(reloadResults) =>
            reloadResults.foreach(logConfigReloadResult)
            scheduleIndexConfigChecking(interval, reloadTask)
          case Left(ex) =>
            logger.error(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Checking index config failed: error", ex)
            scheduleIndexConfigChecking(interval, reloadTask)
        }
    }
  }

  private def logConfigReloadResult(configReloadResult: (ConfigType, Either[ScheduledReloadError, Unit]))
                                   (implicit requestId: RequestId): Unit = configReloadResult match {
    case (_, Right(())) =>
    case (name, Left(ReloadingInProgress)) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Reloading of ${name.show} engine in progress ... skipping")
    case (name, Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.ConfigUpToDate(_))))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] ${name.show} settings are up to date. Nothing to reload.")
    case (name, Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.RorInstanceStopped)))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Stopping periodic ${name.show} settings check - application is being stopped")
    case (name, Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.ReloadingFailed(startingFailure))))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] ReadonlyREST ${name.show} engine starting failed: ${startingFailure.message}")
    case (name, Left(EngineReloadError(IndexConfigReloadError.LoadingConfigError(error)))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Loading ${name.show} config from index failed: ${error.show}")
  }

  private def tryMainEngineReload(requestId: RequestId): Task[Either[ScheduledReloadError, Unit]] = {
    withGuard(mainReloadInProgress) {
      aMainConfigEngine
        .reloadEngineUsingIndexConfigWithoutPermit()(requestId)
        .map(_.map(_ => ()))
        .map(_.leftMap(ScheduledReloadError.EngineReloadError.apply))
    }
  }

  private def tryTestEngineReload(requestId: RequestId): Task[Either[ScheduledReloadError, Unit]] = {
    withGuard(testReloadInProgress) {
      anTestConfigEngine
        .reloadEngineUsingIndexConfigWithoutPermit()(requestId)
        .map(_.leftMap(ScheduledReloadError.EngineReloadError.apply))
    }
  }

  private def withGuard(semaphore: Semaphore[Task])(action: => Task[Either[ScheduledReloadError, Unit]]) = {
    val criticalSection = Resource.make(semaphore.tryAcquire) {
      case true =>
        semaphore.release
      case false =>
        Task.unit
    }
    criticalSection.use {
      case true => action
      case false => Task.now(Left(ScheduledReloadError.ReloadingInProgress))
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
    final case class LoadingConfigError(underlying: ConfigLoaderError[IndexConfigError]) extends IndexConfigReloadError
    final case class ReloadError(underlying: RawConfigReloadError) extends IndexConfigReloadError
  }

  sealed trait IndexConfigUpdateError
  object IndexConfigUpdateError {
    final case class IndexConfigSavingError(underlying: SavingIndexConfigError) extends IndexConfigUpdateError
    case object TestSettingsNotSet extends IndexConfigUpdateError
    case object TestSettingsInvalidated extends IndexConfigUpdateError
  }

  sealed trait IndexConfigInvalidationError
  object IndexConfigInvalidationError {
    final case class IndexConfigSavingError(underlying: SavingIndexConfigError) extends IndexConfigInvalidationError
  }

  private sealed trait ScheduledReloadError
  private object ScheduledReloadError {
    case object ReloadingInProgress extends ScheduledReloadError
    final case class EngineReloadError(underlying: IndexConfigReloadError) extends ScheduledReloadError
  }

  sealed trait TestConfig
  object TestConfig {
    case object NotSet extends TestConfig
    final case class Present(config: RorConfig,
                             rawConfig: RawRorConfig,
                             configuredTtl: PositiveFiniteDuration,
                             validTo: Instant) extends TestConfig
    final case class Invalidated(recent: RawRorConfig,
                                 configuredTtl: PositiveFiniteDuration) extends TestConfig
  }

  def createWithPeriodicIndexCheck(boot: ReadonlyRest,
                                   mainEngine: ReadonlyRest.MainEngine,
                                   testEngine: ReadonlyRest.TestEngine,
                                   rorConfigurationIndex: RorConfigurationIndex)
                                  (implicit environmentConfig: EnvironmentConfig,
                                   scheduler: Scheduler): Task[RorInstance] = {
    create(boot, Mode.WithPeriodicIndexCheck, mainEngine, testEngine, rorConfigurationIndex)
  }

  def createWithoutPeriodicIndexCheck(boot: ReadonlyRest,
                                      mainEngine: ReadonlyRest.MainEngine,
                                      testEngine: ReadonlyRest.TestEngine,
                                      rorConfigurationIndex: RorConfigurationIndex)
                                     (implicit environmentConfig: EnvironmentConfig,
                                      scheduler: Scheduler): Task[RorInstance] = {
    create(boot, Mode.NoPeriodicIndexCheck, mainEngine, testEngine, rorConfigurationIndex)
  }

  private def create(boot: ReadonlyRest,
                     mode: RorInstance.Mode,
                     engine: ReadonlyRest.MainEngine,
                     testEngine: ReadonlyRest.TestEngine,
                     rorConfigurationIndex: RorConfigurationIndex)
                    (implicit environmentConfig: EnvironmentConfig,
                     scheduler: Scheduler) = {
    for {
      isReloadInProgressSemaphore <- Semaphore[Task](1)
      isTestReloadInProgressSemaphore <- Semaphore[Task](1)
    } yield new RorInstance(
      boot = boot,
      mode = mode,
      initialEngine = engine,
      mainReloadInProgress = isReloadInProgressSemaphore,
      initialTestEngine = testEngine,
      testReloadInProgress = isTestReloadInProgressSemaphore,
      rorConfigurationIndex = rorConfigurationIndex
    )
  }

  private sealed trait Mode
  private object Mode {
    case object WithPeriodicIndexCheck extends Mode
    case object NoPeriodicIndexCheck extends Mode
  }

  private sealed trait ConfigType
  private object ConfigType {
    case object Main extends ConfigType
    case object Test extends ConfigType

    implicit val show: Show[ConfigType] = Show.show {
      case Main => "main"
      case Test => "test"
    }
  }
}

