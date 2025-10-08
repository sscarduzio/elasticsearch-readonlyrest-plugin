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
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.blocks.mocks.{AuthServicesMocks, MocksProvider}
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.RorDependencies
import tech.beshu.ror.api.{AuthMockApi, MainSettingsApi, TestSettingsApi}
import tech.beshu.ror.boot.ReadonlyRest.StartingFailure
import tech.beshu.ror.boot.engines.Engines
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.es.LoadingRorCoreStrategy.CoreRefreshSettings
import tech.beshu.ror.settings.es.{EsConfigBasedRorSettings, LoadingRorCoreStrategy}
import tech.beshu.ror.settings.ror.source.IndexSettingsSource
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource.LoadingSettingsError
import tech.beshu.ror.settings.ror.source.ReadWriteSettingsSource.SavingSettingsError
import tech.beshu.ror.settings.ror.{MainRorSettings, RawRorSettings}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class RorInstance private(boot: ReadonlyRest,
                          mode: RorInstance.Mode,
                          esConfigBasedRorSettings: EsConfigBasedRorSettings,
                          creators: SettingsRelatedCreators,
                          mainInitialEngine: ReadonlyRest.MainEngine,
                          mainReloadInProgress: Semaphore[Task],
                          testInitialEngine: ReadonlyRest.TestEngine,
                          testReloadInProgress: Semaphore[Task])
                         (implicit systemContext: SystemContext,
                          scheduler: Scheduler)
  extends Logging {

  import RorInstance.*
  import RorInstance.ScheduledReloadError.{EngineReloadError, ReloadingInProgress}
  import creators.*

  private val reloadTaskState: AtomicReference[ReloadTaskState] = new AtomicReference(ReloadTaskState.NotInitiated)

  mode match {
    case Mode.WithPeriodicIndexCheck(interval) =>
      scheduleEnginesReload(interval)
    case Mode.NoPeriodicIndexCheck =>
      logger.info(s"[CLUSTERWIDE SETTINGS] Scheduling in-index settings check disabled")
  }

  private val theMainSettingsEngine = mainSettingsBasedReloadableEngineCreator.create(
    boot,
    esConfigBasedRorSettings,
    mainInitialEngine,
    mainReloadInProgress
  )
  private val theTestSettingsEngine = testSettingsBasedReloadableEngineCreator.create(
    boot,
    esConfigBasedRorSettings,
    testInitialEngine,
    testReloadInProgress
  )

  private val mainSettingsRestApi = mainSettingsApiCreator.create(this)
  private val testSettingsRestApi = testSettingsApiCreator.create(this)
  private val authMockRestApi = new AuthMockApi(rorInstance = this)

  logger.info("ReadonlyREST was loaded ...")
  
  def engines: Option[Engines] = theMainSettingsEngine.engine.map(Engines(_, theTestSettingsEngine.engine))

  def mainSettingsApi: MainSettingsApi = mainSettingsRestApi

  def authMockApi: AuthMockApi = authMockRestApi

  def testSettingsApi: TestSettingsApi = testSettingsRestApi

  def mocksProvider: MocksProvider = boot.authServicesMocksProvider

  def forceReloadFromIndex()
                          (implicit requestId: RequestId): Task[Either[IndexSettingsReloadError, Unit]] =
    theMainSettingsEngine.forceReloadFromIndex()

  def forceReloadAndSave(settings: RawRorSettings)
                        (implicit requestId: RequestId): Task[Either[IndexSettingsReloadWithUpdateError, Unit]] =
    theMainSettingsEngine.forceReloadAndSave(MainRorSettings(settings))

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
      _ <- Task.delay(logger.info("ReadonlyREST is stopping ..."))
      currentState <- Task.delay(reloadTaskState.getAndSet(ReloadTaskState.Stopped))
      _ <- Task.delay(currentState match {
        case ReloadTaskState.NotInitiated => // do nothing
        case ReloadTaskState.Running(cancelable) => cancelable.cancel()
        case ReloadTaskState.Stopped => // do nothing
      })
      _ <- theTestSettingsEngine.stop()
      _ <- theMainSettingsEngine.stop()
      _ <- Task.delay(logger.info("ReadonlyREST is stopped!"))
    } yield ()
  }

  private def scheduleEnginesReload(interval: PositiveFiniteDuration): Unit = {
    val reloadTask = { (requestId: RequestId) =>
      Task.sequence {
        Seq(
          tryMainEngineReload(requestId).map(result => (SettingsType.Main, result)),
          tryTestEngineReload(requestId).map(result => (SettingsType.Test, result))
        )
      }
    }
    scheduleNextIfNotStopping(interval, reloadTask)
  }

  private def scheduleNextIfNotStopping(interval: PositiveFiniteDuration,
                                        reloadTask: RequestId => Task[Seq[(SettingsType, Either[ScheduledReloadError, Unit])]]): Unit = {
    implicit val requestId: RequestId = RequestId(systemContext.uuidProvider.random.toString)
    val nextTask = scheduleIndexSettingsChecking(interval, reloadTask)
    trySetNextReloadTask(nextTask) match {
      case ReloadTaskState.NotInitiated => // nothing to do
      case ReloadTaskState.Running(_) => // nothing to do
      case ReloadTaskState.Stopped => nextTask.cancel()
    }
  }

  // todo: do we need all of these messages
  private def scheduleIndexSettingsChecking(interval: PositiveFiniteDuration,
                                            reloadTask: RequestId => Task[Seq[(SettingsType, Either[ScheduledReloadError, Unit])]])
                                           (implicit requestId: RequestId): CancelableWithRequestId = {
    logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Scheduling next in-index settings check within ${interval.show}")
    val cancellable = scheduler.scheduleOnce(interval.value) {
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Loading ReadonlyREST settings from index ...")
      reloadTask(requestId)
        .runAsync {
          case Right(reloadResults) =>
            reloadResults.foreach(logSettingsReloadResult)
            scheduleNextIfNotStopping(interval, reloadTask)
          case Left(ex) =>
            logger.error(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Checking index settings failed: error", ex)
            scheduleNextIfNotStopping(interval, reloadTask)
        }
    }
    new CancelableWithRequestId(cancellable, requestId)
  }

  private def trySetNextReloadTask(nextTask: CancelableWithRequestId) = {
    reloadTaskState.updateAndGet {
      case ReloadTaskState.NotInitiated | ReloadTaskState.Running(_) =>
        ReloadTaskState.Running(nextTask)
      case ReloadTaskState.Stopped =>
        ReloadTaskState.Stopped
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
    case (name, Left(EngineReloadError(IndexSettingsReloadError.IndexLoadingSettingsError(error)))) =>
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

  def create(boot: ReadonlyRest,
             esConfigBasedRorSettings: EsConfigBasedRorSettings,
             creators: SettingsRelatedCreators,
             mainEngine: ReadonlyRest.MainEngine,
             testEngine: ReadonlyRest.TestEngine)
            (implicit systemContext: SystemContext,
             scheduler: Scheduler): Task[RorInstance] = {
    val mode = modeFrom(esConfigBasedRorSettings.loadingRorCoreStrategy)
    createInstance(boot, esConfigBasedRorSettings, creators, mode, mainEngine, testEngine)
  }

  private def createInstance(boot: ReadonlyRest,
                             esConfigBasedRorSettings: EsConfigBasedRorSettings,
                             creators: SettingsRelatedCreators,
                             mode: RorInstance.Mode,
                             mainEngine: ReadonlyRest.MainEngine,
                             testEngine: ReadonlyRest.TestEngine)
                            (implicit systemContext: SystemContext,
                             scheduler: Scheduler) = {
    for {
      isReloadInProgressSemaphore <- Semaphore[Task](1)
      isTestReloadInProgressSemaphore <- Semaphore[Task](1)
    } yield new RorInstance(
      boot = boot,
      esConfigBasedRorSettings = esConfigBasedRorSettings,
      creators = creators,
      mode = mode,
      mainInitialEngine = mainEngine,
      mainReloadInProgress = isReloadInProgressSemaphore,
      testInitialEngine = testEngine,
      testReloadInProgress = isTestReloadInProgressSemaphore
    )
  }

  private def modeFrom(strategy: LoadingRorCoreStrategy) = {
    strategy match {
      case LoadingRorCoreStrategy.ForceLoadingFromFile =>
        Mode.NoPeriodicIndexCheck
      case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(_, CoreRefreshSettings.Disabled) =>
        Mode.NoPeriodicIndexCheck
      case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(_, CoreRefreshSettings.Enabled(refreshInterval)) =>
        Mode.WithPeriodicIndexCheck(refreshInterval)
    }
  }

  sealed trait RawSettingsReloadError
  object RawSettingsReloadError {
    final case class ReloadingFailed(failure: StartingFailure) extends RawSettingsReloadError
    final case class SettingsUpToDate(settings: RawRorSettings) extends RawSettingsReloadError
    object RorInstanceStopped extends RawSettingsReloadError
  }

  sealed trait IndexSettingsReloadWithUpdateError
  object IndexSettingsReloadWithUpdateError {
    final case class ReloadError(undefined: RawSettingsReloadError) extends IndexSettingsReloadWithUpdateError
    final case class IndexSettingsSavingError(underlying: SavingSettingsError[IndexSettingsSource.SavingError]) extends IndexSettingsReloadWithUpdateError
  }

  sealed trait IndexSettingsReloadError
  object IndexSettingsReloadError {
    final case class IndexLoadingSettingsError(underlying: LoadingSettingsError[IndexSettingsSource.LoadingError]) extends IndexSettingsReloadError
    final case class ReloadError(underlying: RawSettingsReloadError) extends IndexSettingsReloadError
  }

  sealed trait IndexSettingsUpdateError
  object IndexSettingsUpdateError {
    final case class IndexSettingsSavingError(underlying: SavingSettingsError[IndexSettingsSource.SavingError]) extends IndexSettingsUpdateError
    case object TestSettingsNotSet extends IndexSettingsUpdateError
    case object TestSettingsInvalidated extends IndexSettingsUpdateError
  }

  sealed trait IndexSettingsInvalidationError
  object IndexSettingsInvalidationError {
    final case class IndexSettingsSavingError(underlying: SavingSettingsError[IndexSettingsSource.SavingError]) extends IndexSettingsInvalidationError
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

  private sealed trait Mode
  private object Mode {
    final case class WithPeriodicIndexCheck(reloadInterval: PositiveFiniteDuration) extends Mode
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

  private sealed trait ReloadTaskState
  private object ReloadTaskState {
    case object NotInitiated extends ReloadTaskState
    final case class Running(cancelable: CancelableWithRequestId) extends ReloadTaskState
    case object Stopped extends ReloadTaskState
  }

  private final class CancelableWithRequestId(cancelable: Cancelable, requestId: RequestId)
    extends Logging {

    def cancel(): Unit = {
      logger.debug(s"[CLUSTERWIDE SETTINGS][${requestId.show}] Scheduling next in-index settings check cancelled!")
      cancelable.cancel()
    }
  }
}

