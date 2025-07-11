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
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.boot.ReadonlyRest
import tech.beshu.ror.boot.ReadonlyRest.*
import tech.beshu.ror.boot.RorInstance.*
import tech.beshu.ror.boot.RorInstance.IndexSettingsReloadWithUpdateError.{IndexSettingsSavingError, ReloadError}
import tech.beshu.ror.boot.RorInstance.RawSettingsReloadError.{ReloadingFailed, RorInstanceStopped, SettingsUpToDate}
import tech.beshu.ror.boot.engines.BaseReloadableEngine.InitialEngine
import tech.beshu.ror.boot.engines.ConfigHash.*
import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, RawRorSettings}
import tech.beshu.ror.configuration.loader.RorMainSettingsManager
import tech.beshu.ror.configuration.loader.SettingsManager.{LoadingFromIndexError, SavingIndexSettingsError}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.ScalaOps.value

private[boot] class MainSettingsBasedReloadableEngine(boot: ReadonlyRest,
                                                      esConfig: EsConfigBasedRorSettings,
                                                      initialEngine: (Engine, RawRorSettings),
                                                      reloadInProgress: Semaphore[Task],
                                                      settingsManager: RorMainSettingsManager)
                                                     (implicit systemContext: SystemContext,
                                                      scheduler: Scheduler)
  extends BaseReloadableEngine(
    name = "main",
    boot = boot,
    esConfig = esConfig,
    initialEngine = InitialEngine.Configured(engine = initialEngine._1, config = initialEngine._2, expirationConfig = None),
    reloadInProgress = reloadInProgress
  ) {

  def forceReloadAndSave(config: RawRorSettings)
                        (implicit requestId: RequestId): Task[Either[IndexSettingsReloadWithUpdateError, Unit]] = {
    for {
      _ <- Task.delay(logger.info(s"[${requestId.show}] Reloading of provided settings was forced (new engine id=${config.hashString()}) ..."))
      reloadResult <- reloadInProgress.withPermit {
        value {
          for {
            _ <- reloadEngine(config).leftMap(IndexSettingsReloadWithUpdateError.ReloadError.apply)
            _ <- saveConfig(config)
          } yield ()
        }
      }
      _ <- Task.delay(reloadResult match {
        case Right(_) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${config.hashString().show}) settings reloaded!")
        case Left(ReloadError(SettingsUpToDate(oldConfig))) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${oldConfig.hashString().show}) already loaded!")
        case Left(ReloadError(ReloadingFailed(StartingFailure(message, Some(ex))))) =>
          logger.error(s"[${requestId.show}] [${config.hashString()}] Cannot reload ROR settings - failure: ${message.show}", ex)
        case Left(ReloadError(ReloadingFailed(StartingFailure(message, None)))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ${message.show}")
        case Left(ReloadError(RorInstanceStopped)) =>
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading main settings skipped!")
        case Left(IndexSettingsSavingError(SavingIndexSettingsError.CannotSaveSettings)) =>
          // todo: invalidate created core?
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading main settings skipped!")
      })
    } yield reloadResult
  }

  def forceReloadFromIndex()
                          (implicit requestId: RequestId): Task[Either[IndexSettingsReloadError, Unit]] = {
    for {
      _ <- Task.delay(logger.info(s"[${requestId.show}] Reloading of in-index settings was forced ..."))
      reloadResult <- reloadEngineUsingIndexConfig()
      _ <- Task.delay(reloadResult match {
        case Right(config) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${config.hashString().show}) settings reloaded!")
        case Left(IndexSettingsReloadError.ReloadError(SettingsUpToDate(config))) =>
          logger.info(s"[${requestId.show}] ROR ${name.show} engine (id=${config.hashString().show}) already loaded!")
        case Left(IndexSettingsReloadError.ReloadError(ReloadingFailed(StartingFailure(message, Some(ex))))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ${message.show}", ex)
        case Left(IndexSettingsReloadError.ReloadError(ReloadingFailed(StartingFailure(message, None)))) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ${message.show}")
        case Left(IndexSettingsReloadError.ReloadError(RorInstanceStopped)) =>
          logger.warn(s"[${requestId.show}] ROR is being stopped! Loading main settings skipped!")
        case Left(IndexSettingsReloadError.LoadingSettingsError(error)) =>
          logger.error(s"[${requestId.show}] Cannot reload ROR settings - failure: ${error.show}")
      })
    } yield reloadResult.map(_ => ())
  }

  def reloadEngineUsingIndexConfig()
                                  (implicit requestId: RequestId): Task[Either[IndexSettingsReloadError, RawRorSettings]] = {
    reloadInProgress.withPermit {
      reloadEngineUsingIndexSettingsWithoutPermit()
    }
  }

  private[boot] def reloadEngineUsingIndexSettingsWithoutPermit()
                                                               (implicit requestId: RequestId): Task[Either[IndexSettingsReloadError, RawRorSettings]] = {
    val result = for {
      newSettings <- loadRorSettingFromIndex()
      _ <- reloadEngine(newSettings)
        .leftMap(IndexSettingsReloadError.ReloadError.apply)
        .leftWiden[IndexSettingsReloadError]
    } yield newSettings
    result.value
  }

  private def saveConfig(settings: RawRorSettings): EitherT[Task, IndexSettingsReloadWithUpdateError, Unit] = EitherT {
    for {
      saveResult <- settingsManager.saveToIndex(settings)
    } yield saveResult.left.map(IndexSettingsReloadWithUpdateError.IndexSettingsSavingError.apply)
  }

  private def loadRorSettingFromIndex() = {
    EitherT(settingsManager.loadFromIndex())
      .leftMap {
        case LoadingFromIndexError.IndexParsingError(message) => ???
        case LoadingFromIndexError.IndexUnknownStructure => ???
        case LoadingFromIndexError.IndexNotExist => ???
      }
  }

}
