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
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.mocks.AuthServicesMocks
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.boot.ReadonlyRest.{StartingFailure, TestEngine}
import tech.beshu.ror.boot.RorInstance.IndexConfigReloadWithUpdateError.{IndexConfigSavingError, ReloadError}
import tech.beshu.ror.boot.RorInstance._
import tech.beshu.ror.boot.engines.BaseReloadableEngine.{EngineExpirationConfig, EngineState, InitialEngine}
import tech.beshu.ror.boot.engines.ConfigHash._
import tech.beshu.ror.configuration.TestRorConfig.Present.ExpirationConfig
import tech.beshu.ror.configuration.index.SavingIndexConfigError
import tech.beshu.ror.configuration.{RawRorConfig, TestRorConfig}
import tech.beshu.ror.utils.ScalaOps.value

import java.time.Clock
import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration

private[boot] class TestConfigBasedReloadableEngine private(boot: ReadonlyRest,
                                                            initialEngine: InitialEngine,
                                                            reloadInProgress: Semaphore[Task],
                                                            rorConfigurationIndex: RorConfigurationIndex)
                                                           (implicit scheduler: Scheduler,
                                                            clock: Clock)
  extends BaseReloadableEngine(
    "test", boot, initialEngine, reloadInProgress, rorConfigurationIndex
  ) {

  def currentTestConfig()
                       (implicit @nowarn("cat=unused") requestId: RequestId): Task[TestConfig] = {
    Task.delay {
      currentEngineState match {
        case EngineState.NotStartedYet(None, _) | EngineState.Stopped =>
          TestConfig.NotSet
        case EngineState.NotStartedYet(Some(recentConfig), recentExpirationConfig) =>
          val expiration = recentExpirationConfig.getOrElse(throw new IllegalStateException("Test Config based engine should have an expiration config defined"))
          TestConfig.Invalidated(recentConfig, expiration.ttl)
        case EngineState.Working(engineWithConfig, _) =>
          val expiration = engineWithConfig.expirationConfig.getOrElse(throw new IllegalStateException("Test Config based engine should have an expiration config defined"))
          TestConfig.Present(engineWithConfig.engine.core.rorConfig, engineWithConfig.config, expiration.ttl, expiration.validTo)
      }
    }
  }

  def forceReloadTestConfigEngine(config: RawRorConfig,
                                  ttl: FiniteDuration Refined Positive)
                                 (implicit requestId: RequestId): Task[Either[IndexConfigReloadWithUpdateError, TestConfig.Present]] = {
    for {
      _ <- Task.delay(logger.info(s"[${requestId.show}] Reloading of ROR test settings was forced (TTL of test engine is ${ttl.toString()}) ..."))
      reloadResult <- reloadInProgress.withPermit {
        value {
          for {
            engineExpirationConfig <- reloadEngine(config, ttl).leftMap(IndexConfigReloadWithUpdateError.ReloadError.apply)
            testRorConfig = TestRorConfig.Present(
              rawConfig = config,
              expiration = TestRorConfig.Present.ExpirationConfig(
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
            config = engineExpirationConfig.engine.core.rorConfig,
            rawConfig = config,
            configuredTtl = engineExpirationConfig.expirationConfig.ttl,
            validTo = engineExpirationConfig.expirationConfig.validTo
          )
        }
      }
      _ <- Task.delay(reloadResult match {
        case Right(_) =>
          logger.info(s"[${requestId.show}] ROR $name engine (id=${config.hashString()}) reloaded!")
        case Left(ReloadError(RawConfigReloadError.ConfigUpToDate(oldConfig))) =>
          logger.info(s"[${requestId.show}] ROR $name engine (id=${oldConfig.hashString()}) already loaded!")
        case Left(ReloadError(RawConfigReloadError.ReloadingFailed(StartingFailure(message, Some(ex))))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR test settings - failure: $message", ex)
        case Left(ReloadError(RawConfigReloadError.ReloadingFailed(StartingFailure(message, None)))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR test settings - failure: $message")
        case Left(ReloadError(RawConfigReloadError.RorInstanceStopped)) =>
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading tests settings skipped!")
        case Left(IndexConfigSavingError(SavingIndexConfigError.CannotSaveConfig)) =>
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
            val config = TestRorConfig.Present(
              rawConfig = invalidatedEngine.config,
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
          testRorConfig = TestRorConfig.Present(
            rawConfig = config.rawConfig,
            expiration = TestRorConfig.Present.ExpirationConfig(
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

  private def saveConfigInIndex[A](newConfig: TestRorConfig.Present,
                                   onFailure: SavingIndexConfigError => A): EitherT[Task, A, Unit] = {
    EitherT(boot.indexTestConfigManager.save(newConfig, rorConfigurationIndex))
      .leftMap(onFailure)
  }

  private[boot] def reloadEngineUsingIndexConfigWithoutPermit()
                                                             (implicit requestId: RequestId): Task[Either[IndexConfigReloadError, Unit]] = {
    value {
      for {
        loadedConfig <- loadRorConfigFromIndex()
        config <- loadedConfig match {
          case TestRorConfig.NotSet =>
            invalidateTestConfigByIndex[IndexConfigReloadError]()
          case TestRorConfig.Present(rawConfig, mocks, expiration) =>
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

  private def loadRorConfigFromIndex(): EitherT[Task, IndexConfigReloadError, TestRorConfig] = EitherT {
    boot.indexTestConfigManager
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
             rorConfigurationIndex: RorConfigurationIndex)
            (implicit scheduler: Scheduler,
             clock: Clock): TestConfigBasedReloadableEngine = {
    val engine = initialEngine match {
      case TestEngine.NotConfigured =>
        InitialEngine.NotConfigured
      case TestEngine.Configured(engine, config, expiration) =>
        InitialEngine.Configured(engine, config, Some(expirationConfig(expiration)))
      case TestEngine.Invalidated(config, expiration) =>
        InitialEngine.Invalidated(config, expirationConfig(expiration))
    }
    new TestConfigBasedReloadableEngine(boot, engine, reloadInProgress, rorConfigurationIndex)
  }

  private def expirationConfig(config: TestEngine.Expiration) = EngineExpirationConfig(config.ttl, config.validTo)
}
