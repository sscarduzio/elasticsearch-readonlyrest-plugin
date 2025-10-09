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
import cats.syntax.either.*
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.blocks.mocks.{AuthServicesMocks, MocksProvider}
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.RorDependencies
import tech.beshu.ror.api.{AuthMockApi, MainSettingsApi, TestSettingsApi}
import tech.beshu.ror.boot.ReadonlyRest.StartingFailure
import tech.beshu.ror.boot.engines.Engines
import tech.beshu.ror.settings.es.LoadingRorCoreStrategy.CoreRefreshSettings
import tech.beshu.ror.settings.es.{EsConfigBasedRorSettings, LoadingRorCoreStrategy}
import tech.beshu.ror.settings.ror.source.IndexSettingsSource
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource.LoadingSettingsError
import tech.beshu.ror.settings.ror.source.ReadWriteSettingsSource.SavingSettingsError
import tech.beshu.ror.settings.ror.{MainRorSettings, RawRorSettings}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.time.Instant

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
  import creators.*

  private val settingsAutoReloader = mode match {
    case Mode.WithPeriodicIndexCheck(interval) => new EnabledRorSettingsAutoReloader(interval, this)
    case Mode.NoPeriodicIndexCheck => DisabledRorSettingsAutoReloader
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

  settingsAutoReloader.start()
  logger.info("ReadonlyREST was loaded!")

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
      _ <- settingsAutoReloader.stop()
      _ <- theTestSettingsEngine.stop()
      _ <- theMainSettingsEngine.stop()
      _ <- Task.delay(logger.info("ReadonlyREST is stopped!"))
    } yield ()
  }

  private [boot] def tryMainEngineReload(requestId: RequestId): Task[Either[ScheduledReloadError, Unit]] = {
    withGuard(mainReloadInProgress) {
      theMainSettingsEngine
        .reloadEngineUsingIndexSettingsWithoutPermit()(requestId)
        .map(_.map(_ => ()))
        .map(_.leftMap(ScheduledReloadError.EngineReloadError.apply))
    }
  }

  private [boot] def tryTestEngineReload(requestId: RequestId): Task[Either[ScheduledReloadError, Unit]] = {
    withGuard(testReloadInProgress) {
      theTestSettingsEngine
        .reloadEngineUsingIndexSettingsWithoutPermit()(requestId)
        .map(_.leftMap(ScheduledReloadError.EngineReloadError.apply))
    }
  }

  private def withGuard(semaphore: Semaphore[Task])
                       (action: => Task[Either[ScheduledReloadError, Unit]]) = {
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
    for {
      isReloadInProgressSemaphore <- Semaphore[Task](1)
      isTestReloadInProgressSemaphore <- Semaphore[Task](1)
    } yield new RorInstance(
      boot = boot,
      esConfigBasedRorSettings = esConfigBasedRorSettings,
      creators = creators,
      mode = modeFrom(esConfigBasedRorSettings.loadingRorCoreStrategy),
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
    final case class ReloadError(undefined: RawSettingsReloadError)
      extends IndexSettingsReloadWithUpdateError
    final case class IndexSettingsSavingError(underlying: SavingSettingsError[IndexSettingsSource.SavingError])
      extends IndexSettingsReloadWithUpdateError
  }

  sealed trait IndexSettingsReloadError
  object IndexSettingsReloadError {
    final case class IndexLoadingSettingsError(underlying: LoadingSettingsError[IndexSettingsSource.LoadingError])
      extends IndexSettingsReloadError
    final case class ReloadError(underlying: RawSettingsReloadError)
      extends IndexSettingsReloadError
  }

  sealed trait IndexSettingsUpdateError
  object IndexSettingsUpdateError {
    final case class IndexSettingsSavingError(underlying: SavingSettingsError[IndexSettingsSource.SavingError])
      extends IndexSettingsUpdateError
    case object TestSettingsNotSet
      extends IndexSettingsUpdateError
    case object TestSettingsInvalidated
      extends IndexSettingsUpdateError
  }

  sealed trait IndexSettingsInvalidationError
  object IndexSettingsInvalidationError {
    final case class IndexSettingsSavingError(underlying: SavingSettingsError[IndexSettingsSource.SavingError])
      extends IndexSettingsInvalidationError
  }

  private [boot] sealed trait ScheduledReloadError
  private [boot] object ScheduledReloadError {
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

}

