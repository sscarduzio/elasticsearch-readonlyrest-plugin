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
import cats.syntax.either.*
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import org.apache.logging.log4j.scala.Logging
import squants.information.Information
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.blocks.mocks.{AuthServicesMocks, MocksProvider}
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.RorDependencies
import tech.beshu.ror.api.{AuthMockApi, MainRorSettingsApi, TestRorSettingsApi}
import tech.beshu.ror.boot.engines.{Engines, MainSettingsBasedReloadableEngine, TestSettingsBasedReloadableEngine}
import tech.beshu.ror.configuration.RorProperties.RefreshInterval
import tech.beshu.ror.configuration.manager.SettingsManager.{LoadingFromIndexError, SavingIndexSettingsError}
import tech.beshu.ror.configuration.manager.{RorMainSettingsManager, RorTestSettingsManager}
import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, RawRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.time.Instant

class RorInstance private(boot: ReadonlyRest,
                          mode: RorInstance.Mode,
                          esConfig: EsConfigBasedRorSettings,
                          mainInitialEngine: ReadonlyRest.MainEngine,
                          mainReloadInProgress: Semaphore[Task],
                          mainSettingsManager: RorMainSettingsManager,
                          testInitialEngine: ReadonlyRest.TestEngine,
                          testReloadInProgress: Semaphore[Task],
                          testSettingsManager: RorTestSettingsManager,
                          rorSettingsMaxSize: Information)
                         (implicit systemContext: SystemContext,
                          scheduler: Scheduler)
  extends Logging {

  import RorInstance.*
  import RorInstance.ScheduledReloadError.{EngineReloadError, ReloadingInProgress}

  logger.info("ReadonlyREST was loaded ...")
  private val enginesReloadTask = mode match {
    case Mode.WithPeriodicIndexCheck(RefreshInterval.Enabled(interval)) =>
      scheduleEnginesReload(interval)
    case Mode.WithPeriodicIndexCheck(RefreshInterval.Disabled) | Mode.NoPeriodicIndexCheck =>
      logger.info(s"[CLUSTERWIDE SETTINGS] Scheduling in-index settings check disabled")
      Cancelable.empty
  }

  private val theMainSettingsEngine = new MainSettingsBasedReloadableEngine(
    boot,
    esConfig,
    (mainInitialEngine.engine, mainInitialEngine.settings),
    mainReloadInProgress,
    mainSettingsManager
  )
  private val theTestSettingsEngine = TestSettingsBasedReloadableEngine.create(
    boot,
    esConfig,
    testInitialEngine,
    testReloadInProgress,
    testSettingsManager
  )

  private val rarRorSettingsYamlParser = new RawRorSettingsYamlParser(rorSettingsMaxSize)

  private val mainSettingsRestApi = new MainRorSettingsApi(
    rorInstance = this,
    rarRorSettingsYamlParser,
    mainSettingsManager
  )

  private val authMockRestApi = new AuthMockApi(rorInstance = this)

  private val testSettingsRestApi = new TestRorSettingsApi(rorInstance = this, rarRorSettingsYamlParser)

  def engines: Option[Engines] = theMainSettingsEngine.engine.map(Engines(_, theTestSettingsEngine.engine))

  def mainSettingsApi: MainRorSettingsApi = mainSettingsRestApi

  def authMockApi: AuthMockApi = authMockRestApi

  def testSettingsApi: TestRorSettingsApi = testSettingsRestApi

  def mocksProvider: MocksProvider = boot.authServicesMocksProvider

  def forceReloadFromIndex()
                          (implicit requestId: RequestId): Task[Either[IndexSettingsReloadError, Unit]] =
    theMainSettingsEngine.forceReloadFromIndex()

  def forceReloadAndSave(settings: RawRorSettings)
                        (implicit requestId: RequestId): Task[Either[IndexSettingsReloadWithUpdateError, Unit]] =
    theMainSettingsEngine.forceReloadAndSave(settings)

  def currentTestSettings()
                         (implicit requestId: RequestId): Task[TestSettings] = {
    theTestSettingsEngine.currentTestSettings()
  }

  def forceReloadTestSettingsEngine(settings: RawRorSettings,
                                    ttl: PositiveFiniteDuration)
                                   (implicit requestId: RequestId): Task[Either[IndexSettingsReloadWithUpdateError, TestSettings.Present]] = {
    theTestSettingsEngine.forceReloadTestSettingsEngine(settings, ttl)
  }

  def invalidateTestSettingsEngine()
                                  (implicit requestId: RequestId): Task[Either[IndexSettingsInvalidationError, Unit]] = {
    theTestSettingsEngine.invalidateTestSettingsEngine()
  }

  def updateAuthMocks(mocks: AuthServicesMocks)
                     (implicit requestId: RequestId): Task[Either[IndexSettingsUpdateError, Unit]] = {
    theTestSettingsEngine.saveServicesMocks(mocks)
  }

  def stop(): Task[Unit] = {
    implicit val requestId: RequestId = RequestId("ES sigterm")
    for {
      _ <- Task.delay(enginesReloadTask.cancel())
      _ <- theTestSettingsEngine.stop()
      _ <- theMainSettingsEngine.stop()
    } yield ()
  }

  private def scheduleEnginesReload(interval: PositiveFiniteDuration): Cancelable = {
    val reloadTask = { (requestId: RequestId) =>
      Task.sequence {
        Seq(
          tryMainEngineReload(requestId).map(result => (SettingsType.Main, result)),
          tryTestEngineReload(requestId).map(result => (SettingsType.Test, result))
        )
      }
    }
    scheduleIndexSettingsChecking(interval, reloadTask)
  }

  private def scheduleIndexSettingsChecking(interval: PositiveFiniteDuration,
                                          reloadTask: RequestId => Task[Seq[(SettingsType, Either[ScheduledReloadError, Unit])]]): Cancelable = {
    logger.debug(s"[CLUSTERWIDE SETTINGS] Scheduling next in-index settings check within ${interval.show}")
    scheduler.scheduleOnce(interval.value) {
      implicit val requestId: RequestId = RequestId(systemContext.uuidProvider.random.toString)
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Loading ReadonlyREST settings from index ...")
      reloadTask(requestId)
        .runAsync {
          case Right(reloadResults) =>
            reloadResults.foreach(logSettingsReloadResult)
            scheduleIndexSettingsChecking(interval, reloadTask)
          case Left(ex) =>
            logger.error(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Checking index settings failed: error", ex)
            scheduleIndexSettingsChecking(interval, reloadTask)
        }
    }
  }

  private def logSettingsReloadResult(settingsReloadResult: (SettingsType, Either[ScheduledReloadError, Unit]))
                                   (implicit requestId: RequestId): Unit = settingsReloadResult match {
    case (_, Right(())) =>
    case (name, Left(ReloadingInProgress)) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Reloading of ${name.show} engine in progress ... skipping")
    case (name, Left(EngineReloadError(IndexSettingsReloadError.ReloadError(RawSettingsReloadError.SettingsUpToDate(_))))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] ${name.show} settings are up to date. Nothing to reload.")
    case (name, Left(EngineReloadError(IndexSettingsReloadError.ReloadError(RawSettingsReloadError.RorInstanceStopped)))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Stopping periodic ${name.show} settings check - application is being stopped")
    case (name, Left(EngineReloadError(IndexSettingsReloadError.ReloadError(RawSettingsReloadError.ReloadingFailed(startingFailure))))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] ReadonlyREST ${name.show} engine starting failed: ${startingFailure.message.show}")
    case (name, Left(EngineReloadError(IndexSettingsReloadError.LoadingSettingsError(error)))) =>
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Loading ${name.show} settings from index failed: ${error.show}")
  }

  private def tryMainEngineReload(requestId: RequestId): Task[Either[ScheduledReloadError, Unit]] = {
    withGuard(mainReloadInProgress) {
      theMainSettingsEngine
        .reloadEngineUsingIndexSettingsWithoutPermit()(requestId)
        .map(_.map(_ => ()))
        .map(_.leftMap(ScheduledReloadError.EngineReloadError.apply))
    }
  }

  private def tryTestEngineReload(requestId: RequestId): Task[Either[ScheduledReloadError, Unit]] = {
    withGuard(testReloadInProgress) {
      theTestSettingsEngine
        .reloadEngineUsingIndexSettingsWithoutPermit()(requestId)
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

  sealed trait RawSettingsReloadError
  object RawSettingsReloadError {
    final case class ReloadingFailed(failure: ReadonlyRest.StartingFailure) extends RawSettingsReloadError
    final case class SettingsUpToDate(settings: RawRorSettings) extends RawSettingsReloadError
    object RorInstanceStopped extends RawSettingsReloadError
  }

  sealed trait IndexSettingsReloadWithUpdateError
  object IndexSettingsReloadWithUpdateError {
    final case class ReloadError(undefined: RawSettingsReloadError) extends IndexSettingsReloadWithUpdateError
    final case class IndexSettingsSavingError(underlying: SavingIndexSettingsError) extends IndexSettingsReloadWithUpdateError
  }

  sealed trait IndexSettingsReloadError
  object IndexSettingsReloadError {
    final case class LoadingSettingsError(underlying: LoadingFromIndexError) extends IndexSettingsReloadError
    final case class ReloadError(underlying: RawSettingsReloadError) extends IndexSettingsReloadError
  }

  sealed trait IndexSettingsUpdateError
  object IndexSettingsUpdateError {
    final case class IndexSettingsSavingError(underlying: SavingIndexSettingsError) extends IndexSettingsUpdateError
    case object TestSettingsNotSet extends IndexSettingsUpdateError
    case object TestSettingsInvalidated extends IndexSettingsUpdateError
  }

  sealed trait IndexSettingsInvalidationError
  object IndexSettingsInvalidationError {
    final case class IndexSettingsSavingError(underlying: SavingIndexSettingsError) extends IndexSettingsInvalidationError
  }

  private sealed trait ScheduledReloadError
  private object ScheduledReloadError {
    case object ReloadingInProgress extends ScheduledReloadError
    final case class EngineReloadError(underlying: IndexSettingsReloadError) extends ScheduledReloadError
  }

  sealed trait TestSettings
  object TestSettings {
    case object NotSet extends TestSettings
    final case class Present(rawSettings: RawRorSettings,
                             dependencies: RorDependencies,
                             configuredTtl: PositiveFiniteDuration,
                             validTo: Instant) extends TestSettings
    final case class Invalidated(recent: RawRorSettings,
                                 configuredTtl: PositiveFiniteDuration) extends TestSettings
  }

  def createWithPeriodicIndexCheck(boot: ReadonlyRest,
                                   esConfig: EsConfigBasedRorSettings,
                                   mainEngine: ReadonlyRest.MainEngine,
                                   testEngine: ReadonlyRest.TestEngine,
                                   mainSettingsManager: RorMainSettingsManager,
                                   testSettingsManager: RorTestSettingsManager,
                                   refreshInterval: RefreshInterval,
                                   rorSettingsMaxSize: Information)
                                  (implicit systemContext: SystemContext,
                                   scheduler: Scheduler): Task[RorInstance] = {
    create(boot, esConfig, Mode.WithPeriodicIndexCheck(refreshInterval), mainEngine, testEngine, mainSettingsManager, testSettingsManager, rorSettingsMaxSize)
  }

  def createWithoutPeriodicIndexCheck(boot: ReadonlyRest,
                                      esConfig: EsConfigBasedRorSettings,
                                      mainEngine: ReadonlyRest.MainEngine,
                                      testEngine: ReadonlyRest.TestEngine,
                                      mainSettingsManager: RorMainSettingsManager,
                                      testSettingsManager: RorTestSettingsManager,
                                      rorSettingsMaxSize: Information)
                                     (implicit systemContext: SystemContext,
                                      scheduler: Scheduler): Task[RorInstance] = {
    create(boot, esConfig, Mode.NoPeriodicIndexCheck, mainEngine, testEngine, mainSettingsManager, testSettingsManager, rorSettingsMaxSize)
  }

  private def create(boot: ReadonlyRest,
                     esConfig: EsConfigBasedRorSettings,
                     mode: RorInstance.Mode,
                     mainEngine: ReadonlyRest.MainEngine,
                     testEngine: ReadonlyRest.TestEngine,
                     mainSettingsManager: RorMainSettingsManager,
                     testSettingsManager: RorTestSettingsManager,
                     rorSettingsMaxSize: Information)
                    (implicit systemContext: SystemContext,
                     scheduler: Scheduler) = {
    for {
      isReloadInProgressSemaphore <- Semaphore[Task](1)
      isTestReloadInProgressSemaphore <- Semaphore[Task](1)
    } yield new RorInstance(
      boot = boot,
      esConfig = esConfig,
      mode = mode,
      mainInitialEngine = mainEngine,
      mainReloadInProgress = isReloadInProgressSemaphore,
      mainSettingsManager = mainSettingsManager,
      testInitialEngine = testEngine,
      testReloadInProgress = isTestReloadInProgressSemaphore,
      testSettingsManager = testSettingsManager,
      rorSettingsMaxSize = rorSettingsMaxSize
    )
  }

  private sealed trait Mode
  private object Mode {
    final case class WithPeriodicIndexCheck(reloadInterval: RefreshInterval) extends Mode
    case object NoPeriodicIndexCheck extends Mode
  }

  private sealed trait SettingsType
  private object SettingsType {
    case object Main extends SettingsType
    case object Test extends SettingsType

    implicit val show: Show[SettingsType] = Show.show {
      case Main => "main"
      case Test => "test"
    }
  }
}

