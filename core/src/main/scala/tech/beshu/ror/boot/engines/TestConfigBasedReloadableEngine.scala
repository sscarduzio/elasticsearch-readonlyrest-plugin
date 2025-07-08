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
import tech.beshu.ror.accesscontrol.domain.{RequestId, RorConfigurationIndex}
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.boot.ReadonlyRest.{StartingFailure, TestEngine}
import tech.beshu.ror.boot.RorInstance.*
import tech.beshu.ror.boot.RorInstance.IndexConfigReloadWithUpdateError.{IndexConfigSavingError, ReloadError}
import tech.beshu.ror.boot.engines.BaseReloadableEngine.{EngineExpirationConfig, EngineState, InitialEngine}
import tech.beshu.ror.boot.engines.ConfigHash.*
import tech.beshu.ror.configuration.TestRorSettings.Present.ExpirationConfig
import tech.beshu.ror.configuration.index.IndexSettingsManager
import tech.beshu.ror.configuration.index.IndexSettingsManager.SavingIndexSettingsError
import tech.beshu.ror.configuration.{RawRorSettings, TestRorSettings}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.ScalaOps.value

private[boot] class TestConfigBasedReloadableEngine private(boot: ReadonlyRest,
                                                            initialEngine: InitialEngine,
                                                            reloadInProgress: Semaphore[Task],
                                                            indexTestConfigManager: IndexSettingsManager[TestRorSettings],
                                                            rorConfigurationIndex: RorConfigurationIndex)
                                                           (implicit systemContext: SystemContext,
                                                            scheduler: Scheduler)
  extends BaseReloadableEngine(
    "test", boot, initialEngine, reloadInProgress, rorConfigurationIndex
  ) {

  def currentTestConfig()
                       (implicit requestId: RequestId): Task[TestConfig] = {
    Task.delay {
      currentEngineState match {
        case EngineState.NotStartedYet(None, _) | EngineState.Stopped =>
          TestConfig.NotSet
        case EngineState.NotStartedYet(Some(recentConfig), recentExpirationConfig) =>
          val expiration = recentExpirationConfig.getOrElse(throw new IllegalStateException("Test Config based engine should have an expiration config defined"))
          TestConfig.Invalidated(recentConfig, expiration.ttl)
        case EngineState.Working(engineWithConfig, _) =>
          val expiration = engineWithConfig.expirationConfig.getOrElse(throw new IllegalStateException("Test Config based engine should have an expiration config defined"))
          TestConfig.Present(engineWithConfig.config, engineWithConfig.engine.core.dependencies, expiration.ttl, expiration.validTo)
      }
    }
  }

  def forceReloadTestConfigEngine(config: RawRorSettings,
                                  ttl: PositiveFiniteDuration)
                                 (implicit requestId: RequestId): Task[Either[IndexConfigReloadWithUpdateError, TestConfig.Present]] = {
    for {
      _ <- Task.delay(logger.info(s"[${requestId.show}] Reloading of ROR test settings was forced (TTL of test engine is ${ttl.show}) ..."))
      reloadResult <- reloadInProgress.withPermit {
        value {
          for {
            engineExpirationConfig <- reloadEngine(config, ttl).leftMap(IndexConfigReloadWithUpdateError.ReloadError.apply)
            testRorConfig = TestRorSettings.Present(
              rawSettings = config,
              expiration = TestRorSettings.Present.ExpirationConfig(
                ttl = engineExpirationConfig.expirationConfig.ttl,
                validTo = engineExpirationConfig.expirationConfig.validTo
              ),
              mocks = boot.authServicesMocksProvider.currentMocks
            )
            _ <- saveConfigInIndex(
              newConfig = testRorConfig,
              onFailure = IndexConfigReloadWithUpdateError.IndexConfigSavingError.apply
            )
              .leftWiden[IndexConfigReloadWithUpdateError]
          } yield TestConfig.Present(
            dependencies = engineExpirationConfig.engine.core.dependencies,
            rawConfig = config,
            configuredTtl = engineExpirationConfig.expirationConfig.ttl,
            validTo = engineExpirationConfig.expirationConfig.validTo
          )
        }
      }
      _ <- Task.delay(reloadResult match {
        case Right(_) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${config.hashString().show}) reloaded!")
        case Left(ReloadError(RawConfigReloadError.ConfigUpToDate(oldConfig))) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${oldConfig.hashString().show}) already loaded!")
        case Left(ReloadError(RawConfigReloadError.ReloadingFailed(StartingFailure(message, Some(ex))))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR test settings - failure: ${message.show}", ex)
        case Left(ReloadError(RawConfigReloadError.ReloadingFailed(StartingFailure(message, None)))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR test settings - failure: ${message.show}")
        case Left(ReloadError(RawConfigReloadError.RorInstanceStopped)) =>
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading tests settings skipped!")
        case Left(IndexConfigSavingError(SavingIndexSettingsError.CannotSaveSettings)) =>
          logger.error(s"[${requestId.show}] Saving ROR test settings in index failed")
      })
    } yield reloadResult
  }

  def invalidateTestConfigEngine()
                                (implicit requestId: RequestId): Task[Either[IndexConfigInvalidationError, Unit]] = {
    reloadInProgress.withPermit {
      for {
        invalidated <- invalidate(keepPreviousConfiguration = true)
        result <- invalidated match {
          case Some(invalidatedEngine) =>
            val config = TestRorSettings.Present(
              rawSettings = invalidatedEngine.config,
              expiration = ExpirationConfig(
                ttl = invalidatedEngine.expirationConfig.ttl,
                validTo = invalidatedEngine.expirationConfig.validTo
              ),
              mocks = boot.authServicesMocksProvider.currentMocks
            )
            saveConfigInIndex(
              newConfig = config,
              onFailure = IndexConfigInvalidationError.IndexConfigSavingError.apply
            )
              .leftWiden[IndexConfigInvalidationError]
              .value
          case None =>
            Task.now(Right(()))
        }
      } yield result
    }
  }

  def saveConfig(mocks: AuthServicesMocks)
                (implicit requestId: RequestId): Task[Either[IndexConfigUpdateError, Unit]] = {
    reloadInProgress.withPermit {
      value {
        for {
          config <- readCurrentTestConfigForUpdate()
          _ <- updateMocksProvider(mocks)
          testRorConfig = TestRorSettings.Present(
            rawSettings = config.rawConfig,
            expiration = TestRorSettings.Present.ExpirationConfig(
              ttl = config.configuredTtl,
              validTo = config.validTo
            ),
            mocks = boot.authServicesMocksProvider.currentMocks
          )
          _ <- saveConfigInIndex[IndexConfigUpdateError](
            newConfig = testRorConfig,
            onFailure = IndexConfigUpdateError.IndexConfigSavingError.apply
          )
        } yield ()
      }
    }
  }

  private def readCurrentTestConfigForUpdate()
                                            (implicit requestId: RequestId): EitherT[Task, IndexConfigUpdateError, TestConfig.Present] = {
    EitherT {
      currentTestConfig()
        .map {
          case TestConfig.NotSet =>
            Left(IndexConfigUpdateError.TestSettingsNotSet)
          case config: TestConfig.Present =>
            Right(config)
          case _: TestConfig.Invalidated =>
            Left(IndexConfigUpdateError.TestSettingsInvalidated)
        }
    }
  }

  private def updateMocksProvider[A](mocks: AuthServicesMocks): EitherT[Task, A, Unit] = {
    EitherT.right(Task.delay(boot.authServicesMocksProvider.update(mocks)))
  }

  private def saveConfigInIndex[A](newConfig: TestRorSettings.Present,
                                   onFailure: SavingIndexSettingsError => A): EitherT[Task, A, Unit] = {
    EitherT(indexTestConfigManager.save(newConfig, rorConfigurationIndex))
      .leftMap(onFailure)
  }

  private[boot] def reloadEngineUsingIndexConfigWithoutPermit()
                                                             (implicit requestId: RequestId): Task[Either[IndexConfigReloadError, Unit]] = {
    value {
      for {
        loadedConfig <- loadRorConfigFromIndex()
        config <- loadedConfig match {
          case TestRorSettings.NotSet =>
            invalidateTestConfigByIndex[IndexConfigReloadError]()
          case TestRorSettings.Present(rawConfig, mocks, expiration) =>
            for {
              _ <- reloadEngine(rawConfig, expiration.validTo, expiration.ttl)
                .leftMap(IndexConfigReloadError.ReloadError.apply)
                .leftWiden[IndexConfigReloadError]
              _ <- updateMocksProvider[IndexConfigReloadError](mocks)
            } yield ()
        }
      } yield config
    }
  }

  private def loadRorConfigFromIndex(): EitherT[Task, IndexConfigReloadError, TestRorSettings] = EitherT {
    indexTestConfigManager
      .load(rorConfigurationIndex)
      .map(_.left.map(IndexConfigReloadError.LoadingConfigError.apply))
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

object TestConfigBasedReloadableEngine {
  def create(boot: ReadonlyRest,
             initialEngine: ReadonlyRest.TestEngine,
             reloadInProgress: Semaphore[Task],
             indexTestConfigManager: IndexSettingsManager[TestRorSettings],
             rorConfigurationIndex: RorConfigurationIndex)
            (implicit systemContext: SystemContext,
             scheduler: Scheduler): TestConfigBasedReloadableEngine = {
    val engine = initialEngine match {
      case TestEngine.NotConfigured =>
        InitialEngine.NotConfigured
      case TestEngine.Configured(engine, config, expiration) =>
        InitialEngine.Configured(engine, config, Some(expirationConfig(expiration)))
      case TestEngine.Invalidated(config, expiration) =>
        InitialEngine.Invalidated(config, expirationConfig(expiration))
    }
    new TestConfigBasedReloadableEngine(boot, engine, reloadInProgress, indexTestConfigManager, rorConfigurationIndex)
  }

  private def expirationConfig(config: TestEngine.Expiration) = EngineExpirationConfig(config.ttl, config.validTo)
}
