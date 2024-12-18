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

import tech.beshu.ror.implicits.*
import cats.data.EitherT
import cats.implicits.*
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.boot.ReadonlyRest.*
import tech.beshu.ror.boot.RorInstance.IndexConfigReloadWithUpdateError.{IndexConfigSavingError, ReloadError}
import tech.beshu.ror.boot.RorInstance.RawConfigReloadError.{ConfigUpToDate, ReloadingFailed, RorInstanceStopped}
import tech.beshu.ror.boot.RorInstance.*
import tech.beshu.ror.boot.engines.BaseReloadableEngine.InitialEngine
import tech.beshu.ror.boot.engines.ConfigHash.*
import tech.beshu.ror.configuration.{EnvironmentConfig, RawRorConfig}
import tech.beshu.ror.configuration.index.SavingIndexConfigError.CannotSaveConfig
import tech.beshu.ror.utils.ScalaOps.value

private[boot] class MainConfigBasedReloadableEngine(boot: ReadonlyRest,
                                                    initialEngine: (Engine, RawRorConfig),
                                                    reloadInProgress: Semaphore[Task],
                                                    rorConfigurationIndex: RorConfigurationIndex)
                                                   (implicit environmentConfig: EnvironmentConfig,
                                                    scheduler: Scheduler)
  extends BaseReloadableEngine(
    name = "main",
    boot = boot,
    initialEngine = InitialEngine.Configured(engine = initialEngine._1, config = initialEngine._2, expirationConfig = None),
    reloadInProgress = reloadInProgress,
    rorConfigurationIndex = rorConfigurationIndex
  ) {

  def forceReloadAndSave(config: RawRorConfig)
                        (implicit requestId: RequestId): Task[Either[IndexConfigReloadWithUpdateError, Unit]] = {
    for {
      _ <- Task.delay(logger.info(s"[${requestId.show}] Reloading of provided settings was forced (new engine id=${config.hashString()}) ..."))
      reloadResult <- reloadInProgress.withPermit {
        value {
          for {
            _ <- reloadEngine(config).leftMap(IndexConfigReloadWithUpdateError.ReloadError.apply)
            _ <- saveConfig(config)
          } yield ()
        }
      }
      _ <- Task.delay(reloadResult match {
        case Right(_) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${config.hashString().show}) settings reloaded!")
        case Left(ReloadError(ConfigUpToDate(oldConfig))) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${oldConfig.hashString().show}) already loaded!")
        case Left(ReloadError(ReloadingFailed(StartingFailure(message, Some(ex))))) =>
          logger.error(s"[${requestId.show}] [${config.hashString()}] Cannot reload ROR settings - failure: ${message.show}", ex)
        case Left(ReloadError(ReloadingFailed(StartingFailure(message, None)))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ${message.show}")
        case Left(ReloadError(RorInstanceStopped)) =>
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading main settings skipped!")
        case Left(IndexConfigSavingError(CannotSaveConfig)) =>
          // todo: invalidate created core?
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading main settings skipped!")
      })
    } yield reloadResult
  }

  def forceReloadFromIndex()
                          (implicit requestId: RequestId): Task[Either[IndexConfigReloadError, Unit]] = {
    for {
      _ <- Task.delay(logger.info(s"[${requestId.show}] Reloading of in-index settings was forced ..."))
      reloadResult <- reloadEngineUsingIndexConfig()
      _ <- Task.delay(reloadResult match {
        case Right(config) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${config.hashString().show}) settings reloaded!")
        case Left(IndexConfigReloadError.ReloadError(ConfigUpToDate(config))) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${config.hashString().show}) already loaded!")
        case Left(IndexConfigReloadError.ReloadError(ReloadingFailed(StartingFailure(message, Some(ex))))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ${message.show}", ex)
        case Left(IndexConfigReloadError.ReloadError(ReloadingFailed(StartingFailure(message, None)))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ${message.show}")
        case Left(IndexConfigReloadError.ReloadError(RorInstanceStopped)) =>
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading main settings skipped!")
        case Left(IndexConfigReloadError.LoadingConfigError(error)) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ${error.show}")
      })
    } yield reloadResult.map(_ => ())
  }

  def reloadEngineUsingIndexConfig()
                                  (implicit requestId: RequestId): Task[Either[IndexConfigReloadError, RawRorConfig]] = {
    reloadInProgress.withPermit {
      reloadEngineUsingIndexConfigWithoutPermit()
    }
  }

  private[boot] def reloadEngineUsingIndexConfigWithoutPermit()
                                                             (implicit requestId: RequestId): Task[Either[IndexConfigReloadError, RawRorConfig]] = {
    val result = for {
      newConfig <- EitherT(loadRorConfigFromIndex())
      _ <- reloadEngine(newConfig)
        .leftMap(IndexConfigReloadError.ReloadError.apply)
        .leftWiden[IndexConfigReloadError]
    } yield newConfig
    result.value
  }

  private def saveConfig(newConfig: RawRorConfig): EitherT[Task, IndexConfigReloadWithUpdateError, Unit] = EitherT {
    for {
      saveResult <- boot.indexConfigManager.save(newConfig, rorConfigurationIndex)
    } yield saveResult.left.map(IndexConfigReloadWithUpdateError.IndexConfigSavingError.apply)
  }

  private def loadRorConfigFromIndex() = {
    boot.indexConfigManager
      .load(rorConfigurationIndex)
      .map(_.left.map(IndexConfigReloadError.LoadingConfigError.apply))
  }

}
