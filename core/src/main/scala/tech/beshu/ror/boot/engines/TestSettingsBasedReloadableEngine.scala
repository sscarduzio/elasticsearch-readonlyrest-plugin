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
package tech.beshu.ror.boot.engines

import cats.data.EitherT
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.blocks.mocks.AuthServicesMocks
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.boot.ReadonlyRest.{StartingFailure, TestEngine}
import tech.beshu.ror.boot.RorInstance.*
import tech.beshu.ror.boot.RorInstance.IndexSettingsReloadWithUpdateError.{IndexSettingsSavingError, ReloadError}
import tech.beshu.ror.boot.engines.BaseReloadableEngine.{EngineExpiration, EngineState, InitialEngine}
import tech.beshu.ror.boot.engines.SettingsHash.*
import tech.beshu.ror.configuration.TestRorSettings.Present.Expiration
import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, RawRorSettings, TestRorSettings}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.source.IndexSettingsSource
import tech.beshu.ror.settings.source.ReadWriteSettingsSource.SavingSettingsError
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.ScalaOps.value

private[boot] class TestSettingsBasedReloadableEngine private(boot: ReadonlyRest,
                                                              esConfig: EsConfigBasedRorSettings,
                                                              initialEngine: InitialEngine,
                                                              reloadInProgress: Semaphore[Task],
                                                              testSettingsSource: IndexSettingsSource[TestRorSettings])
                                                             (implicit systemContext: SystemContext,
                                                              scheduler: Scheduler)
  extends BaseReloadableEngine("test", boot, esConfig, initialEngine, reloadInProgress) {

  def currentTestSettings()
                         (implicit requestId: RequestId): Task[TestSettings] = {
    Task.delay {
      currentEngineState match {
        case EngineState.NotStartedYet(None, _) | EngineState.Stopped =>
          TestSettings.NotSet
        case EngineState.NotStartedYet(Some(recentSettings), recentExpiration) =>
          val expiration = recentExpiration.getOrElse(throw new IllegalStateException("Test settings based engine should have an expiration defined"))
          TestSettings.Invalidated(recentSettings, expiration.ttl)
        case EngineState.Working(engineWithSettings, _) =>
          val expiration = engineWithSettings.expiration.getOrElse(throw new IllegalStateException("Test settings based engine should have an expiration defined"))
          TestSettings.Present(engineWithSettings.settings, engineWithSettings.engine.core.dependencies, expiration.ttl, expiration.validTo)
      }
    }
  }

  def forceReloadTestSettingsEngine(settings: RawRorSettings,
                                    ttl: PositiveFiniteDuration)
                                   (implicit requestId: RequestId): Task[Either[IndexSettingsReloadWithUpdateError, TestSettings.Present]] = {
    for {
      _ <- Task.delay(logger.info(s"[${requestId.show}] Reloading of ROR test settings was forced (TTL of test engine is ${ttl.show}) ..."))
      reloadResult <- reloadInProgress.withPermit {
        value {
          for {
            engineExpiration <- reloadEngine(settings, ttl).leftMap(IndexSettingsReloadWithUpdateError.ReloadError.apply)
            testRorSettings = TestRorSettings.Present(
              rawSettings = settings,
              expiration = TestRorSettings.Present.Expiration(
                ttl = engineExpiration.expiration.ttl,
                validTo = engineExpiration.expiration.validTo
              ),
              mocks = boot.authServicesMocksProvider.currentMocks
            )
            _ <- saveSettingsInIndex(
              newSettings = testRorSettings,
              onFailure = IndexSettingsReloadWithUpdateError.IndexSettingsSavingError.apply
            )
              .leftWiden[IndexSettingsReloadWithUpdateError]
          } yield TestSettings.Present(
            dependencies = engineExpiration.engine.core.dependencies,
            rawSettings = settings,
            configuredTtl = engineExpiration.expiration.ttl,
            validTo = engineExpiration.expiration.validTo
          )
        }
      }
      _ <- Task.delay(reloadResult match {
        case Right(_) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${settings.hashString().show}) reloaded!")
        case Left(ReloadError(RawSettingsReloadError.SettingsUpToDate(oldSettings))) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${oldSettings.hashString().show}) already loaded!")
        case Left(ReloadError(RawSettingsReloadError.ReloadingFailed(StartingFailure(message, Some(ex))))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR test settings - failure: ${message.show}", ex)
        case Left(ReloadError(RawSettingsReloadError.ReloadingFailed(StartingFailure(message, None)))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR test settings - failure: ${message.show}")
        case Left(ReloadError(RawSettingsReloadError.RorInstanceStopped)) =>
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading tests settings skipped!")
        case Left(IndexSettingsSavingError(_)) => // todo: SavingIndexSettingsError.CannotSaveSettings)) =>
          logger.error(s"[${requestId.show}] Saving ROR test settings in index failed")
      })
    } yield reloadResult
  }

  def invalidateTestSettingsEngine()
                                  (implicit requestId: RequestId): Task[Either[IndexSettingsInvalidationError, Unit]] = {
    reloadInProgress.withPermit {
      for {
        invalidated <- invalidate(keepPreviousSettings = true)
        result <- invalidated match {
          case Some(invalidatedEngine) =>
            val settings = TestRorSettings.Present(
              rawSettings = invalidatedEngine.settings,
              expiration = Expiration(
                ttl = invalidatedEngine.expiration.ttl,
                validTo = invalidatedEngine.expiration.validTo
              ),
              mocks = boot.authServicesMocksProvider.currentMocks
            )
            saveSettingsInIndex(
              newSettings = settings,
              onFailure = IndexSettingsInvalidationError.IndexSettingsSavingError.apply
            )
              .leftWiden[IndexSettingsInvalidationError]
              .value
          case None =>
            Task.now(Right(()))
        }
      } yield result
    }
  }

  def saveServicesMocks(mocks: AuthServicesMocks)
                       (implicit requestId: RequestId): Task[Either[IndexSettingsUpdateError, Unit]] = {
    reloadInProgress.withPermit {
      value {
        for {
          settings <- readCurrentTestSettingsForUpdate()
          _ <- updateMocksProvider(mocks)
          testRorSettings = TestRorSettings.Present(
            rawSettings = settings.rawSettings,
            expiration = TestRorSettings.Present.Expiration(
              ttl = settings.configuredTtl,
              validTo = settings.validTo
            ),
            mocks = boot.authServicesMocksProvider.currentMocks
          )
          _ <- saveSettingsInIndex[IndexSettingsUpdateError](
            newSettings = testRorSettings,
            onFailure = IndexSettingsUpdateError.IndexSettingsSavingError.apply
          )
        } yield ()
      }
    }
  }

  private def readCurrentTestSettingsForUpdate()
                                            (implicit requestId: RequestId): EitherT[Task, IndexSettingsUpdateError, TestSettings.Present] = {
    EitherT {
      currentTestSettings()
        .map {
          case TestSettings.NotSet =>
            Left(IndexSettingsUpdateError.TestSettingsNotSet)
          case settings: TestSettings.Present =>
            Right(settings)
          case _: TestSettings.Invalidated =>
            Left(IndexSettingsUpdateError.TestSettingsInvalidated)
        }
    }
  }

  private def updateMocksProvider[A](mocks: AuthServicesMocks): EitherT[Task, A, Unit] = {
    EitherT.right(Task.delay(boot.authServicesMocksProvider.update(mocks)))
  }

  private def saveSettingsInIndex[A](newSettings: TestRorSettings.Present,
                                     onFailure: SavingSettingsError => A): EitherT[Task, A, Unit] = {
    EitherT(testSettingsSource.save(newSettings))
      .leftMap(onFailure)
  }

  private[boot] def reloadEngineUsingIndexSettingsWithoutPermit()
                                                               (implicit requestId: RequestId): Task[Either[IndexSettingsReloadError, Unit]] = {
    value {
      for {
        loadedSettings <- loadRorTestSettingsFromIndex()
        result <- loadedSettings match {
          case TestRorSettings.NotSet =>
            invalidateTestSettingsByIndex[IndexSettingsReloadError]()
          case TestRorSettings.Present(rawSettings, mocks, expiration) =>
            for {
              _ <- reloadEngine(rawSettings, expiration.validTo, expiration.ttl)
                .leftMap(IndexSettingsReloadError.ReloadError.apply)
                .leftWiden[IndexSettingsReloadError]
              _ <- updateMocksProvider[IndexSettingsReloadError](mocks)
            } yield ()
        }
      } yield result
    }
  }

  private def loadRorTestSettingsFromIndex(): EitherT[Task, IndexSettingsReloadError, TestRorSettings] = {
    EitherT(testSettingsSource.load())
      .leftMap(IndexSettingsReloadError.IndexLoadingSettingsError.apply)
  }

  private def invalidateTestSettingsByIndex[A]()
                                            (implicit requestId: RequestId): EitherT[Task, A, Unit] = {
    EitherT.right[A] {
      for {
        _ <-
          invalidate(keepPreviousSettings = false)
            .map {
              case Some(_) => ()
              case None => ()
            }
        _ <- invalidateAuthMocks()
      } yield ()
    }
  }

  private def invalidateAuthMocks(): Task[Unit] = Task.delay(boot.authServicesMocksProvider.invalidate())

}

object TestSettingsBasedReloadableEngine {
  def create(boot: ReadonlyRest,
             esConfig: EsConfigBasedRorSettings,
             initialEngine: ReadonlyRest.TestEngine,
             reloadInProgress: Semaphore[Task],
             testSettingsSource: IndexSettingsSource[TestRorSettings])
            (implicit systemContext: SystemContext,
             scheduler: Scheduler): TestSettingsBasedReloadableEngine = {
    val engine = initialEngine match {
      case TestEngine.NotConfigured =>
        InitialEngine.NotConfigured
      case TestEngine.Configured(engine, settings, expiration) =>
        InitialEngine.Configured(engine, settings, Some(engineExpiration(expiration)))
      case TestEngine.Invalidated(settings, expiration) =>
        InitialEngine.Invalidated(settings, engineExpiration(expiration))
    }
    new TestSettingsBasedReloadableEngine(boot, esConfig, engine, reloadInProgress, testSettingsSource)
  }

  private def engineExpiration(expiration: TestEngine.Expiration) = EngineExpiration(expiration.ttl, expiration.validTo)
}
