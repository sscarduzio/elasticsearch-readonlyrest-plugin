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
import tech.beshu.ror.boot.engines.BaseReloadableEngine.{EngineExpirationConfig, EngineState, InitialEngine}
import tech.beshu.ror.boot.engines.ConfigHash.*
import tech.beshu.ror.configuration.TestRorSettings.Present.Expiration
import tech.beshu.ror.configuration.loader.RorTestSettingsManager
import tech.beshu.ror.configuration.loader.SettingsManager.SavingIndexSettingsError
import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, RawRorSettings, TestRorSettings}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.ScalaOps.value

private[boot] class TestSettingsBasedReloadableEngine private(boot: ReadonlyRest,
                                                              esConfig: EsConfigBasedRorSettings,
                                                              initialEngine: InitialEngine,
                                                              reloadInProgress: Semaphore[Task],
                                                              testSettingsManager: RorTestSettingsManager)
                                                             (implicit systemContext: SystemContext,
                                                              scheduler: Scheduler)
  extends BaseReloadableEngine("test", boot, esConfig, initialEngine, reloadInProgress) {

  def currentTestSettings()
                         (implicit requestId: RequestId): Task[TestSettings] = {
    Task.delay {
      currentEngineState match {
        case EngineState.NotStartedYet(None, _) | EngineState.Stopped =>
          TestSettings.NotSet
        case EngineState.NotStartedYet(Some(recentConfig), recentExpirationConfig) =>
          val expiration = recentExpirationConfig.getOrElse(throw new IllegalStateException("Test Config based engine should have an expiration config defined"))
          TestSettings.Invalidated(recentConfig, expiration.ttl)
        case EngineState.Working(engineWithConfig, _) =>
          val expiration = engineWithConfig.expirationConfig.getOrElse(throw new IllegalStateException("Test Config based engine should have an expiration config defined"))
          TestSettings.Present(engineWithConfig.config, engineWithConfig.engine.core.dependencies, expiration.ttl, expiration.validTo)
      }
    }
  }

  def forceReloadTestSettingsEngine(config: RawRorSettings,
                                    ttl: PositiveFiniteDuration)
                                   (implicit requestId: RequestId): Task[Either[IndexSettingsReloadWithUpdateError, TestSettings.Present]] = {
    for {
      _ <- Task.delay(logger.info(s"[${requestId.show}] Reloading of ROR test settings was forced (TTL of test engine is ${ttl.show}) ..."))
      reloadResult <- reloadInProgress.withPermit {
        value {
          for {
            engineExpirationConfig <- reloadEngine(config, ttl).leftMap(IndexSettingsReloadWithUpdateError.ReloadError.apply)
            testRorConfig = TestRorSettings.Present(
              rawSettings = config,
              expiration = TestRorSettings.Present.Expiration(
                ttl = engineExpirationConfig.expirationConfig.ttl,
                validTo = engineExpirationConfig.expirationConfig.validTo
              ),
              mocks = boot.authServicesMocksProvider.currentMocks
            )
            _ <- saveConfigInIndex(
              newConfig = testRorConfig,
              onFailure = IndexSettingsReloadWithUpdateError.IndexSettingsSavingError.apply
            )
              .leftWiden[IndexSettingsReloadWithUpdateError]
          } yield TestSettings.Present(
            dependencies = engineExpirationConfig.engine.core.dependencies,
            rawSettings = config,
            configuredTtl = engineExpirationConfig.expirationConfig.ttl,
            validTo = engineExpirationConfig.expirationConfig.validTo
          )
        }
      }
      _ <- Task.delay(reloadResult match {
        case Right(_) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${config.hashString().show}) reloaded!")
        case Left(ReloadError(RawSettingsReloadError.SettingsUpToDate(oldConfig))) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${oldConfig.hashString().show}) already loaded!")
        case Left(ReloadError(RawSettingsReloadError.ReloadingFailed(StartingFailure(message, Some(ex))))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR test settings - failure: ${message.show}", ex)
        case Left(ReloadError(RawSettingsReloadError.ReloadingFailed(StartingFailure(message, None)))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR test settings - failure: ${message.show}")
        case Left(ReloadError(RawSettingsReloadError.RorInstanceStopped)) =>
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading tests settings skipped!")
        case Left(IndexSettingsSavingError(SavingIndexSettingsError.CannotSaveSettings)) =>
          logger.error(s"[${requestId.show}] Saving ROR test settings in index failed")
      })
    } yield reloadResult
  }

  def invalidateTestSettingsEngine()
                                  (implicit requestId: RequestId): Task[Either[IndexSettingsInvalidationError, Unit]] = {
    reloadInProgress.withPermit {
      for {
        invalidated <- invalidate(keepPreviousConfiguration = true)
        result <- invalidated match {
          case Some(invalidatedEngine) =>
            val config = TestRorSettings.Present(
              rawSettings = invalidatedEngine.config,
              expiration = Expiration(
                ttl = invalidatedEngine.expirationConfig.ttl,
                validTo = invalidatedEngine.expirationConfig.validTo
              ),
              mocks = boot.authServicesMocksProvider.currentMocks
            )
            saveConfigInIndex(
              newConfig = config,
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
          config <- readCurrentTestConfigForUpdate()
          _ <- updateMocksProvider(mocks)
          testRorConfig = TestRorSettings.Present(
            rawSettings = config.rawSettings,
            expiration = TestRorSettings.Present.Expiration(
              ttl = config.configuredTtl,
              validTo = config.validTo
            ),
            mocks = boot.authServicesMocksProvider.currentMocks
          )
          _ <- saveConfigInIndex[IndexSettingsUpdateError](
            newConfig = testRorConfig,
            onFailure = IndexSettingsUpdateError.IndexSettingsSavingError.apply
          )
        } yield ()
      }
    }
  }

  private def readCurrentTestConfigForUpdate()
                                            (implicit requestId: RequestId): EitherT[Task, IndexSettingsUpdateError, TestSettings.Present] = {
    EitherT {
      currentTestSettings()
        .map {
          case TestSettings.NotSet =>
            Left(IndexSettingsUpdateError.TestSettingsNotSet)
          case config: TestSettings.Present =>
            Right(config)
          case _: TestSettings.Invalidated =>
            Left(IndexSettingsUpdateError.TestSettingsInvalidated)
        }
    }
  }

  private def updateMocksProvider[A](mocks: AuthServicesMocks): EitherT[Task, A, Unit] = {
    EitherT.right(Task.delay(boot.authServicesMocksProvider.update(mocks)))
  }

  private def saveConfigInIndex[A](newConfig: TestRorSettings.Present,
                                   onFailure: SavingIndexSettingsError => A): EitherT[Task, A, Unit] = {
    EitherT(testSettingsManager.saveToIndex(newConfig))
      .leftMap(onFailure)
  }

  private[boot] def reloadEngineUsingIndexSettingsWithoutPermit()
                                                               (implicit requestId: RequestId): Task[Either[IndexSettingsReloadError, Unit]] = {
    value {
      for {
        loadedConfig <- loadRorConfigFromIndex()
        config <- loadedConfig match {
          case TestRorSettings.NotSet =>
            invalidateTestConfigByIndex[IndexSettingsReloadError]()
          case TestRorSettings.Present(rawConfig, mocks, expiration) =>
            for {
              _ <- reloadEngine(rawConfig, expiration.validTo, expiration.ttl)
                .leftMap(IndexSettingsReloadError.ReloadError.apply)
                .leftWiden[IndexSettingsReloadError]
              _ <- updateMocksProvider[IndexSettingsReloadError](mocks)
            } yield ()
        }
      } yield config
    }
  }

  private def loadRorConfigFromIndex(): EitherT[Task, IndexSettingsReloadError, TestRorSettings] = EitherT {
    testSettingsManager
      .loadFromIndex()
      .map(_.left.map(IndexSettingsReloadError.LoadingSettingsError.apply))
  }

  private def invalidateTestConfigByIndex[A]()
                                            (implicit requestId: RequestId): EitherT[Task, A, Unit] = {
    EitherT.right[A] {
      for {
        _ <-
          invalidate(keepPreviousConfiguration = false)
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
             testSettingsManager: RorTestSettingsManager)
            (implicit systemContext: SystemContext,
             scheduler: Scheduler): TestSettingsBasedReloadableEngine = {
    val engine = initialEngine match {
      case TestEngine.NotConfigured =>
        InitialEngine.NotConfigured
      case TestEngine.Configured(engine, config, expiration) =>
        InitialEngine.Configured(engine, config, Some(expirationConfig(expiration)))
      case TestEngine.Invalidated(config, expiration) =>
        InitialEngine.Invalidated(config, expirationConfig(expiration))
    }
    new TestSettingsBasedReloadableEngine(boot, esConfig, engine, reloadInProgress, testSettingsManager)
  }

  private def expirationConfig(config: TestEngine.Expiration) = EngineExpirationConfig(config.ttl, config.validTo)
}
